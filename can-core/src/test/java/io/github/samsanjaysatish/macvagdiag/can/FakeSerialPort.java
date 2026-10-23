package io.github.samsanjaysatish.macvagdiag.can;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A fake serial port: the test feeds bytes for the adapter to read and inspects every byte written.
 * {@code read} polls an in-memory queue with a short real timeout, like the real port's read timeout.
 */
final class FakeSerialPort implements SerialPortIo {

    private static final byte[] END = new byte[0];

    private final LinkedBlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
    private final ByteArrayOutputStream written = new ByteArrayOutputStream();
    private volatile boolean open;
    private volatile boolean closed;
    private volatile int openCount;
    private volatile int closeCount;
    volatile IOException failOpen;
    volatile IOException failWrite;
    volatile IOException failRead;

    @Override
    public String name() {
        return "/dev/cu.usbmodemFAKE";
    }

    @Override
    public void open() throws IOException {
        if (failOpen != null) {
            throw failOpen;
        }
        incoming.removeIf(chunk -> chunk == END); // left over from a previous close
        open = true;
        closed = false;
        openCount++;
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        if (closed || !open) {
            return -1;
        }
        IOException failure = failRead;
        if (failure != null) {
            throw failure;
        }
        byte[] chunk;
        try {
            chunk = incoming.poll(20, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
        if (chunk == null) {
            return 0;
        }
        if (chunk == END) {
            return -1;
        }
        System.arraycopy(chunk, 0, buffer, 0, chunk.length);
        return chunk.length;
    }

    @Override
    public synchronized void write(byte[] bytes) throws IOException {
        if (!open) {
            throw new IOException("fake port not open");
        }
        if (failWrite != null) {
            throw failWrite;
        }
        written.write(bytes, 0, bytes.length);
    }

    @Override
    public void close() {
        open = false;
        closed = true;
        closeCount++;
        incoming.offer(END);
    }

    /** Bytes the adapter will read, as one read() chunk. */
    void feed(String ascii) {
        incoming.offer(ascii.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Simulates unplugging: the next read returns -1. */
    void unplug() {
        incoming.offer(END);
    }

    synchronized String writtenText() {
        return written.toString(StandardCharsets.ISO_8859_1);
    }

    synchronized int writtenByteCount() {
        return written.size();
    }

    boolean isOpen() {
        return open;
    }

    int openCount() {
        return openCount;
    }

    int closeCount() {
        return closeCount;
    }
}
