package io.github.samsanjaysatish.macvagdiag.can;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SlcanAdapterTest {

    private static final Duration WAIT = Duration.ofSeconds(1);

    private final FakeSerialPort port = new FakeSerialPort();
    private final FakeTimeSource time = new FakeTimeSource();
    private final SlcanAdapter adapter = new SlcanAdapter(port, time);

    @AfterEach
    void closeAdapter() {
        adapter.close();
    }

    /** Runs a receive on another thread, so the test can advance the clock or close meanwhile. */
    private CompletableFuture<Optional<TimestampedFrame>> receiveAsync(Duration timeout) {
        CompletableFuture<Optional<TimestampedFrame>> result = new CompletableFuture<>();
        Thread.ofPlatform().daemon(true).start(() -> {
            try {
                result.complete(adapter.receive(timeout));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    private CanFrame receiveFrame() throws InterruptedException {
        return adapter.receive(WAIT).orElseThrow().frame();
    }

    @Test
    @DisplayName("open at 500 kbit/s sends C, then S6 (bitrate) before O (open)")
    void openSequenceSendsBitrateBeforeOpen() {
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        assertEquals("C\rS6\rO\r", port.writtenText());
        assertTrue(adapter.isOpen());
        assertEquals(Optional.of(AdapterMode.NORMAL), adapter.mode());
    }

    @Test
    @DisplayName("open at 125 kbit/s sends S4")
    void openSequenceOtherBitrate() {
        adapter.open(Bitrate.KBIT_125, AdapterMode.NORMAL);
        assertEquals("C\rS4\rO\r", port.writtenText());
    }

    @Test
    @DisplayName("send 7E0 [8] 02 10 03 00 00 00 00 00 writes t7E080210030000000000\\r")
    void sendWritesEncodedFrame() {
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        adapter.send(CanFrame.standardHex(0x7E0, "02 10 03 00 00 00 00 00"));
        assertEquals("C\rS6\rO\rt7E080210030000000000\r", port.writtenText());
    }

    @Test
    @DisplayName("receive t7E880641000000000000 as 7E8 [8], stamped with the TimeSource")
    void receivesFrameFromPort() throws Exception {
        time.advanceMillis(1234);
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        port.feed("t7E880641000000000000\r");
        TimestampedFrame received = adapter.receive(WAIT).orElseThrow();
        assertEquals(CanFrame.standardHex(0x7E8, "06 41 00 00 00 00 00 00"), received.frame());
        assertEquals(Duration.ofMillis(1234).toNanos(), received.monotonicNanos());
        assertEquals(FakeTimeSource.DEFAULT_WALL_START.plusMillis(1234), received.wallClock());
    }

    @Test
    void receivesLinesSplitAcrossReadsAndSeveralPerRead() throws Exception {
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        port.feed("t2000\rt201");
        port.feed("1AA\rT18DAF11023E00\r");
        assertEquals(CanFrame.standard(0x200), receiveFrame());
        assertEquals(CanFrame.standard(0x201, 0xAA), receiveFrame());
        assertEquals(CanFrame.extended(0x18DA_F110, 0x3E, 0x00), receiveFrame());
    }

    @Test
    @DisplayName("receive(500 ms) times out only when the fake clock passes 500 ms")
    void receiveTimesOutOnFakeClock() throws Exception {
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        CompletableFuture<Optional<TimestampedFrame>> result = receiveAsync(Duration.ofMillis(500));
        time.awaitWaiters(1);
        time.advanceMillis(499);
        time.awaitWaiters(1); // woke, rechecked, and is waiting again
        assertFalse(result.isDone());
        time.advanceMillis(1);
        assertEquals(Optional.empty(), result.get());
    }

    @Test
    void receiveWithZeroTimeoutReturnsImmediately() throws Exception {
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        assertEquals(Optional.empty(), adapter.receive(Duration.ZERO));
    }

    @Test
    @DisplayName("malformed lines and \\a replies are counted and the reader keeps going")
    void malformedLinesAreCountedAndSkipped() throws Exception {
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        port.feed("garbage\r\u0007t7E02GG00\rr7E08\rt2000\r");
        assertEquals(CanFrame.standard(0x200), receiveFrame());
        assertEquals(2, adapter.decodeErrorCount());
        assertEquals(1, adapter.errorReplyCount());
        assertEquals(1, adapter.remoteFrameCount());
    }

    @Test
    @DisplayName("close sends C, releases the port and stops the reader thread")
    void closeSendsCloseAndReleasesPort() {
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        adapter.close();
        assertTrue(port.writtenText().endsWith("O\rC\r"), port.writtenText());
        assertFalse(port.isOpen());
        assertFalse(adapter.isOpen());
        assertEquals(Optional.empty(), adapter.mode());
        assertFalse(readerThreadAlive());
    }

    @Test
    void closeAfterWriteErrorStillReleasesPort() {
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        port.failWrite = new IOException("cable pulled");
        assertDoesNotThrow(adapter::close);
        assertFalse(port.isOpen());
        assertFalse(adapter.isOpen());
        assertFalse(readerThreadAlive());
    }

    @Test
    void closeIsIdempotentAndSafeBeforeOpen() {
        assertDoesNotThrow(adapter::close);
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        adapter.close();
        adapter.close();
        assertEquals(1, port.closeCount());
    }

    @Test
    void closeWakesABlockedReceiver() throws Exception {
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        CompletableFuture<Optional<TimestampedFrame>> result = receiveAsync(Duration.ofSeconds(10));
        time.awaitWaiters(1);
        adapter.close();
        ExecutionException e = assertThrows(ExecutionException.class, result::get);
        assertInstanceOf(CanAdapterException.class, e.getCause());
    }

    @Test
    void unpluggedAdapterFailsReceiveAfterDeliveringEarlierFrames() throws Exception {
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        port.feed("t2000\r");
        port.unplug();
        assertEquals(CanFrame.standard(0x200), receiveFrame());
        CanAdapterException e = assertThrows(CanAdapterException.class, () -> adapter.receive(WAIT));
        assertTrue(e.getMessage().contains("closed unexpectedly"), e.getMessage());
    }

    @Test
    void readErrorFailsReceive() {
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        port.failRead = new IOException("I/O error");
        CanAdapterException e = assertThrows(CanAdapterException.class, () -> adapter.receive(WAIT));
        assertTrue(e.getMessage().contains("Read failed"), e.getMessage());
    }

    @Test
    void portThatCannotOpenLeavesAdapterClosed() {
        port.failOpen = new IOException("Resource busy");
        CanAdapterException e = assertThrows(CanAdapterException.class,
                () -> adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL));
        assertInstanceOf(IOException.class, e.getCause());
        assertFalse(adapter.isOpen());
    }

    @Test
    void writeErrorDuringOpenReleasesPortAndLeavesAdapterClosed() {
        port.failWrite = new IOException("write failed");
        assertThrows(CanAdapterException.class, () -> adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL));
        assertFalse(adapter.isOpen());
        assertFalse(port.isOpen());
        assertFalse(readerThreadAlive());
    }

    @Test
    void sendWriteErrorIsReported() {
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        port.failWrite = new IOException("write failed");
        assertThrows(CanAdapterException.class, () -> adapter.send(CanFrame.standard(0x200)));
    }

    @Test
    void sendAndReceiveWhenClosedThrow() {
        assertThrows(CanAdapterException.class, () -> adapter.send(CanFrame.standard(0x200)));
        assertThrows(CanAdapterException.class, () -> adapter.receive(WAIT));
        assertEquals(0, port.writtenByteCount());
    }

    @Test
    void openTwiceThrows() {
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        assertThrows(CanAdapterException.class, () -> adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL));
    }

    @Test
    void reopenAfterClose() throws Exception {
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        adapter.close();
        adapter.open(Bitrate.KBIT_250, AdapterMode.NORMAL);
        port.feed("t2000\r");
        assertEquals(CanFrame.standard(0x200), receiveFrame());
        assertEquals("C\rS6\rO\rC\rC\rS5\rO\r", port.writtenText());
    }

    @Test
    @DisplayName("LISTEN_ONLY is refused before the port is touched: the stock firmware's M1 does not take effect")
    void listenOnlyIsRefusedWithoutTouchingThePort() {
        CanAdapterException e = assertThrows(CanAdapterException.class,
                () -> adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY));
        assertTrue(e.getMessage().contains("refuses LISTEN_ONLY"), e.getMessage());
        assertTrue(e.getMessage().contains("can-core/CLAUDE.md"), e.getMessage());
        assertFalse(adapter.isOpen());
        assertEquals(0, port.openCount(), "serial port never opened");
        assertEquals(0, port.writtenByteCount(), "not a single byte written, not even S6 or M1");
        assertThrows(CanAdapterException.class, () -> adapter.send(CanFrame.standard(0x7E0, 0x02, 0x10, 0x03)));
        assertEquals(0, port.writtenByteCount());
    }

    @Test
    void factoryDoesNotOpenAnything() {
        SlcanAdapter real = SlcanAdapter.forPort("/dev/cu.usbmodemDOESNOTEXIST");
        assertEquals("/dev/cu.usbmodemDOESNOTEXIST", real.portName());
        assertFalse(real.isOpen());
        // LISTEN_ONLY is refused before jSerialComm is even asked for the port.
        assertThrows(CanAdapterException.class, () -> real.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY));
    }

    private static boolean readerThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.isAlive() && t.getName().equals("slcan-reader /dev/cu.usbmodemFAKE"));
    }
}
