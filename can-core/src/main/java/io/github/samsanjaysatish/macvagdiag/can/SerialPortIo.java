package io.github.samsanjaysatish.macvagdiag.can;

import java.io.IOException;

/**
 * The few serial-port operations {@link SlcanAdapter} needs, so tests can feed bytes through a fake.
 * The real implementation is {@link JSerialCommPort}; it is created only by {@link SlcanAdapter#forPort}.
 */
interface SerialPortIo {

    /** Port path for logs, e.g. {@code /dev/cu.usbmodem14101}. */
    String name();

    void open() throws IOException;

    /**
     * Reads into {@code buffer}, blocking at most a short time (about 100 ms).
     *
     * @return bytes read, 0 if none arrived in time, or -1 once the port is closed or gone
     */
    int read(byte[] buffer) throws IOException;

    /** Writes all of {@code bytes}. */
    void write(byte[] bytes) throws IOException;

    /** Releases the port and makes a blocked {@link #read} return. Safe to call more than once. */
    void close();
}
