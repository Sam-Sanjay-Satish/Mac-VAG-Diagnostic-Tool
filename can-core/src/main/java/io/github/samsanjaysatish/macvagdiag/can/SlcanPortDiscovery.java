package io.github.samsanjaysatish.macvagdiag.can;

import com.fazecast.jSerialComm.SerialPort;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Finds serial ports that look like a CANable. On macOS a CANable shows up as {@code /dev/cu.usbmodem*}
 * (and {@code /dev/tty.usbmodem*}); use the {@code cu.*} device, since opening {@code tty.*} waits for a
 * carrier-detect signal that USB CDC adapters never raise.
 */
public final class SlcanPortDiscovery {

    /** Path prefix of candidate ports. */
    public static final String CANDIDATE_PREFIX = "/dev/cu.usbmodem";
    /** USB vendor ID of the CANable 2.0 stock slcan firmware ({@code 16D0}, canable2-fw usbd_desc.c). */
    public static final int CANABLE_VENDOR_ID = 0x16D0;
    /** USB product ID of the CANable 2.0 stock slcan firmware ({@code 117E}). */
    public static final int CANABLE_PRODUCT_ID = 0x117E;

    /**
     * A candidate port.
     *
     * @param path e.g. {@code /dev/cu.usbmodem14101}
     * @param description the USB product description, if the OS reports one. The stock firmware's is
     *     {@code "CANable2 <git version> <git remote>"}, which identifies the firmware build.
     * @param vendorId USB vendor ID, or -1 if unknown
     * @param productId USB product ID, or -1 if unknown
     */
    public record Candidate(String path, String description, int vendorId, int productId) {

        /** USB IDs match the CANable stock slcan firmware. */
        public boolean isCanable() {
            return vendorId == CANABLE_VENDOR_ID && productId == CANABLE_PRODUCT_ID;
        }
    }

    private SlcanPortDiscovery() {
    }

    /** Candidate ports on this machine, sorted by path, CANables first. */
    public static List<Candidate> candidates() {
        List<Candidate> all = new ArrayList<>();
        for (SerialPort p : SerialPort.getCommPorts()) {
            all.add(new Candidate(p.getSystemPortPath(), p.getPortDescription(), p.getVendorID(), p.getProductID()));
        }
        return filter(all);
    }

    /** Keeps only {@code /dev/cu.usbmodem*} ports, sorted with CANables first, then by path. */
    static List<Candidate> filter(Collection<Candidate> ports) {
        return ports.stream()
                .filter(c -> c.path() != null && c.path().startsWith(CANDIDATE_PREFIX))
                .sorted(Comparator.comparing((Candidate c) -> !c.isCanable()).thenComparing(Candidate::path))
                .toList();
    }
}
