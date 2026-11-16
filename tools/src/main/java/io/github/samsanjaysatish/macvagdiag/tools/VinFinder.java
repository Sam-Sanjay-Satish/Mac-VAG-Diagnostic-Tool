package io.github.samsanjaysatish.macvagdiag.tools;

import io.github.samsanjaysatish.macvagdiag.tools.DiagPayloads.Message;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the VIN without being told it: in a capture, from the responses that carry it; in a VCDS log, from its
 * {@code VIN:} fields.
 */
final class VinFinder {

    /** Positive responses whose payload starts with the VIN (17 ASCII bytes right after the prefix). */
    enum VinResponse {
        OBD_09_02(new byte[] {0x49, 0x02, 0x01}, "OBD-II 09 02 response (49 02 01)"),
        UDS_F190(new byte[] {0x62, (byte) 0xF1, (byte) 0x90}, "UDS 22 F1 90 response (62 F1 90)"),
        KWP_1A_90(new byte[] {0x5A, (byte) 0x90}, "KWP 1A 90 response (5A 90)");

        private final byte[] prefix;
        private final String label;

        VinResponse(byte[] prefix, String label) {
            this.prefix = prefix;
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /**
     * A VIN found in a message.
     *
     * @param vin the VIN (never print it)
     * @param message where it was found
     * @param offset its first byte in the message
     * @param where human description without the value, e.g. {@code 7E8 ISO-TP, OBD-II 09 02 response}
     */
    record Found(String vin, Message message, int offset, String where) {
    }

    private static final Pattern VCDS_VIN = Pattern.compile("\\bVIN:[ \\t]*([A-Za-z0-9]{17})\\b");

    private VinFinder() {
    }

    /** VINs in the known VIN responses, in capture order. */
    static List<Found> inResponses(List<Message> messages) {
        List<Found> found = new ArrayList<>();
        for (Message m : messages) {
            byte[] b = m.bytes();
            for (VinResponse r : VinResponse.values()) {
                int end = r.prefix.length + Vin.LENGTH;
                if (b.length >= end && Arrays.equals(b, 0, r.prefix.length, r.prefix, 0, r.prefix.length)) {
                    String vin = new String(b, r.prefix.length, Vin.LENGTH, StandardCharsets.ISO_8859_1);
                    if (Vin.isWellFormed(vin)) {
                        found.add(new Found(vin, m, r.prefix.length, m.idText() + " " + m.protocol().label()
                                + ", " + r.label()));
                    }
                }
            }
        }
        return found;
    }

    /**
     * Other 17-character runs of VIN characters with a valid check digit, which might be a VIN in a response we
     * don't know. Reported for review, never masked automatically (module serial numbers match by chance).
     */
    static List<Found> vinLike(List<Message> messages, Set<String> known) {
        List<Found> found = new ArrayList<>();
        for (Message m : messages) {
            byte[] b = m.bytes();
            for (int i = 0; i + Vin.LENGTH <= b.length; i++) {
                String s = new String(b, i, Vin.LENGTH, StandardCharsets.ISO_8859_1);
                if (Vin.hasValidCheckDigit(s) && !known.contains(s)) {
                    found.add(new Found(s, m, i, m.idText() + " " + m.protocol().label()));
                }
            }
        }
        return found;
    }

    /** The VINs written in a VCDS log's {@code VIN:} fields (uppercased; only well-formed ones). */
    static Set<String> inVcdsLog(String text) {
        Set<String> vins = new LinkedHashSet<>();
        Matcher m = VCDS_VIN.matcher(text);
        while (m.find()) {
            String vin = m.group(1).toUpperCase(Locale.ROOT);
            if (Vin.isWellFormed(vin)) {
                vins.add(vin);
            }
        }
        return vins;
    }
}
