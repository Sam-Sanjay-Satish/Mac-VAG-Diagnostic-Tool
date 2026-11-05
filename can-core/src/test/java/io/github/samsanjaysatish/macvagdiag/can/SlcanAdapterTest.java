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
    @DisplayName("NORMAL open at 500 kbit/s sends C, then S6 (bitrate) and M0 (normal mode) before O (open)")
    void openSequenceSendsBitrateBeforeOpen() {
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        assertEquals("C\rS6\rM0\rO\r", port.writtenText());
        assertTrue(adapter.isOpen());
        assertEquals(Optional.of(AdapterMode.NORMAL), adapter.mode());
    }

    @Test
    @DisplayName("open at 125 kbit/s sends S4")
    void openSequenceOtherBitrate() {
        adapter.open(Bitrate.KBIT_125, AdapterMode.NORMAL);
        assertEquals("C\rS4\rM0\rO\r", port.writtenText());
    }

    @Test
    @DisplayName("send 7E0 [8] 02 10 03 00 00 00 00 00 writes t7E080210030000000000\\r")
    void sendWritesEncodedFrame() {
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        adapter.send(CanFrame.standardHex(0x7E0, "02 10 03 00 00 00 00 00"));
        assertEquals("C\rS6\rM0\rO\rt7E080210030000000000\r", port.writtenText());
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
        assertEquals("C\rS6\rM0\rO\rC\rC\rS5\rM0\rO\r", port.writtenText());
    }

    /** Reply to V from our patched firmware. */
    static final String SILENT_FIRMWARE_REPLY =
            "macvagdiag-silent-1 github.com/Sam-Sanjay-Satish/Mac-VAG-Diagnostic-Tool\r";
    /** Reply to V from the stock CANable 2.0 firmware. */
    static final String STOCK_FIRMWARE_REPLY = "b158aa7 github.com/normaldotcom/canable2.git\r";

    @Test
    @DisplayName("LISTEN_ONLY on firmware macvagdiag-silent-1 sends C, V, then S6, M1 (silent) before O")
    void listenOnlyOnSilentFirmwareSendsSilentOpenSequence() throws Exception {
        port.feed(SILENT_FIRMWARE_REPLY);
        adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY);
        assertEquals("C\rV\rS6\rM1\rO\r", port.writtenText());
        assertEquals(Optional.of(AdapterMode.LISTEN_ONLY), adapter.mode());

        assertThrows(ListenOnlyViolationException.class,
                () -> adapter.send(CanFrame.standardHex(0x7E0, "02 10 03 00 00 00 00 00")));
        assertEquals("C\rV\rS6\rM1\rO\r", port.writtenText(), "nothing written by the refused send");

        port.feed("t7E880641000000000000\r");
        assertEquals(CanFrame.standardHex(0x7E8, "06 41 00 00 00 00 00 00"), receiveFrame());
        adapter.close();
        assertEquals("C\rV\rS6\rM1\rO\rC\r", port.writtenText());
    }

    @Test
    void listenOnlySkipsFramesFromBeforeTheOpenAndAcceptsASplitVersionReply() throws Exception {
        port.feed("t2000\rmacvagdiag-sil");
        port.feed(SILENT_FIRMWARE_REPLY.substring("macvagdiag-sil".length()));
        adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY);
        assertEquals("C\rV\rS6\rM1\rO\r", port.writtenText());
        assertEquals(Optional.empty(), adapter.receive(Duration.ZERO), "the frame from before the open is dropped");
    }

    @Test
    @DisplayName("LISTEN_ONLY on stock firmware b158aa7 is refused after C and V: no S, M or O")
    void listenOnlyIsRefusedOnStockFirmware() {
        port.feed(STOCK_FIRMWARE_REPLY);
        CanAdapterException e = assertThrows(CanAdapterException.class,
                () -> adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY));
        assertTrue(e.getMessage().contains("refuses LISTEN_ONLY"), e.getMessage());
        assertTrue(e.getMessage().contains("'b158aa7'"), e.getMessage());
        assertTrue(e.getMessage().contains("macvagdiag-silent-1"), e.getMessage());
        assertTrue(e.getMessage().contains("firmware/canable2/README.md"), e.getMessage());
        assertRefusedAfterCloseAndVersionOnly();
    }

    @Test
    @DisplayName("LISTEN_ONLY on a dirty or unknown build is refused: the version must match exactly")
    void listenOnlyNeedsAnExactVersionMatch() {
        for (String version : new String[] {"macvagdiag-silent-1-dirty", "macvagdiag-silent-2", "MACVAGDIAG-SILENT-1"}) {
            FakeSerialPort p = new FakeSerialPort();
            SlcanAdapter a = new SlcanAdapter(p, time);
            p.feed(version + " github.com/someone/canable2-fw.git\r");
            CanAdapterException e = assertThrows(CanAdapterException.class,
                    () -> a.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY), version);
            assertTrue(e.getMessage().contains("'" + version + "'"), e.getMessage());
            assertEquals("C\rV\r", p.writtenText(), version);
            assertFalse(p.isOpen(), version);
        }
    }

    @Test
    @DisplayName("LISTEN_ONLY without a reply to V is refused after C and V")
    void listenOnlyIsRefusedWithoutAVersionReply() {
        port.feed("t2000\r"); // traffic, but no version reply
        CanAdapterException e = assertThrows(CanAdapterException.class,
                () -> adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY));
        assertTrue(e.getMessage().contains("no reply to V"), e.getMessage());
        assertRefusedAfterCloseAndVersionOnly();
    }

    @Test
    void listenOnlyReadErrorWhileWaitingForTheVersionLeavesAdapterClosed() {
        port.failRead = new IOException("cable pulled");
        CanAdapterException e = assertThrows(CanAdapterException.class,
                () -> adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY));
        assertInstanceOf(IOException.class, e.getCause());
        port.failRead = null;
        assertRefusedAfterCloseAndVersionOnly();
    }

    @Test
    void refusedListenOnlyCanBeRetriedAfterTheFirmwareIsFixed() throws Exception {
        port.feed(STOCK_FIRMWARE_REPLY);
        assertThrows(CanAdapterException.class, () -> adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY));
        port.feed(SILENT_FIRMWARE_REPLY);
        adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY);
        assertEquals("C\rV\rC\rV\rS6\rM1\rO\r", port.writtenText());
    }

    @Test
    @DisplayName("NORMAL after a LISTEN_ONLY session sends M0, because the patched firmware keeps the mode")
    void normalAfterListenOnlySetsNormalModeAgain() {
        port.feed(SILENT_FIRMWARE_REPLY);
        adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY);
        adapter.close();
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        assertEquals("C\rV\rS6\rM1\rO\rC\rC\rS6\rM0\rO\r", port.writtenText());
    }

    private void assertRefusedAfterCloseAndVersionOnly() {
        assertFalse(adapter.isOpen());
        assertFalse(port.isOpen(), "port released");
        assertFalse(readerThreadAlive());
        assertEquals("C\rV\r", port.writtenText(), "only C (off the bus) and V (version) were sent");
        assertThrows(CanAdapterException.class, () -> adapter.send(CanFrame.standard(0x7E0, 0x02, 0x10, 0x03)));
        assertEquals("C\rV\r", port.writtenText());
    }

    @Test
    void factoryDoesNotOpenAnything() {
        SlcanAdapter real = SlcanAdapter.forPort("/dev/cu.usbmodemDOESNOTEXIST");
        assertEquals("/dev/cu.usbmodemDOESNOTEXIST", real.portName());
        assertFalse(real.isOpen());
    }

    private static boolean readerThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.isAlive() && t.getName().equals("slcan-reader /dev/cu.usbmodemFAKE"));
    }
}
