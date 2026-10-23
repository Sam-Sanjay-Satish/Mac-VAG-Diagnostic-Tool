package io.github.samsanjaysatish.macvagdiag.can;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortInvalidPortException;
import java.io.IOException;

/** {@link SerialPortIo} on jSerialComm. Not covered by unit tests: it needs a real port. */
final class JSerialCommPort implements SerialPortIo {

    /** USB CDC ignores the baud rate; any value works. */
    private static final int BAUD = 115_200;
    private static final int READ_TIMEOUT_MS = 100;
    private static final int WRITE_TIMEOUT_MS = 1_000;

    private final String path;
    private volatile SerialPort port;

    JSerialCommPort(String path) {
        this.path = path;
    }

    @Override
    public String name() {
        return path;
    }

    @Override
    public synchronized void open() throws IOException {
        SerialPort p;
        try {
            p = SerialPort.getCommPort(path);
        } catch (SerialPortInvalidPortException e) {
            throw new IOException("No such serial port: " + path, e);
        }
        p.setComPortParameters(BAUD, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING,
                READ_TIMEOUT_MS, WRITE_TIMEOUT_MS);
        if (!p.openPort()) {
            throw new IOException("Could not open serial port " + path
                    + " (in use by another program, or unplugged?)");
        }
        port = p;
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        SerialPort p = port;
        if (p == null || !p.isOpen()) {
            return -1;
        }
        int n = p.readBytes(buffer, buffer.length);
        if (n < 0) {
            if (!p.isOpen()) {
                return -1;
            }
            throw new IOException("Read failed on " + path);
        }
        return n;
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        SerialPort p = port;
        if (p == null) {
            throw new IOException(path + " is not open");
        }
        int n = p.writeBytes(bytes, bytes.length);
        if (n != bytes.length) {
            throw new IOException("Write failed on " + path + ": wrote " + n + " of " + bytes.length + " bytes");
        }
    }

    @Override
    public synchronized void close() {
        if (port != null) {
            port.closePort();
            port = null;
        }
    }
}
