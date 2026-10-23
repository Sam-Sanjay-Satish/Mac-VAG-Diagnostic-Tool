package io.github.samsanjaysatish.macvagdiag.transport;

import io.github.samsanjaysatish.macvagdiag.can.Hex;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The read-only request allowlist: the single checkpoint every diagnostic request passes before it is
 * segmented and put on the bus (root CLAUDE.md, safety rules 1–4).
 *
 * <p>A request is allowed only if it matches one {@link Entry} exactly: same application protocol, the
 * entry's fixed leading bytes (service ID, and the sub-function where the service has one), and exactly the
 * entry's total length. Everything else is refused: unknown protocol, unknown service, unlisted sub-function,
 * wrong length, null or empty payload.
 *
 * <p><b>The allowlist is a fixed constant.</b> There is no config, setter, environment variable or test hook to
 * change it. Changing {@link #ALLOWLIST} needs the user's explicit approval in the conversation (rule 4), and
 * {@code RequestGuardTest} pins its exact contents so any change fails a test until it is reviewed.
 *
 * <p>Approved by the user on 2026-10-22, provisional until checked against the sniffing capture. KWP
 * {@code 21 xx} (measuring blocks) is deliberately excluded from v1.
 */
public final class RequestGuard {

    private static final Logger log = LoggerFactory.getLogger(RequestGuard.class);

    // Service IDs on the allowlist, named as they appear on the bus.
    static final int SID_START_SESSION = 0x10;
    static final int SID_KWP_READ_FREEZE_FRAME = 0x12;
    static final int SID_KWP_READ_DTC_BY_STATUS = 0x18;
    static final int SID_UDS_READ_DTC_INFO = 0x19;
    static final int SID_KWP_READ_ECU_IDENTIFICATION = 0x1A;
    static final int SID_UDS_READ_DATA_BY_ID = 0x22;
    static final int SID_TESTER_PRESENT = 0x3E;

    /**
     * One allowed request shape.
     *
     * @param protocol the channel protocol it applies to
     * @param prefix the bytes that must match exactly: service ID, plus sub-function where the service has one
     * @param length the exact total payload length; bytes after the prefix may have any value
     * @param description what it is, for the allowlist table and logs
     */
    record Entry(ApplicationProtocol protocol, byte[] prefix, int length, String description) {

        Entry {
            prefix = prefix.clone();
        }

        @Override
        public byte[] prefix() {
            return prefix.clone();
        }

        boolean matches(ApplicationProtocol p, byte[] payload) {
            if (p != protocol || payload.length != length) {
                return false;
            }
            for (int i = 0; i < prefix.length; i++) {
                if (payload[i] != prefix[i]) {
                    return false;
                }
            }
            return true;
        }

        /** Bus-style pattern, e.g. {@code 19 04 xx xx xx xx}. */
        String pattern() {
            StringBuilder sb = new StringBuilder(Hex.format(prefix));
            for (int i = prefix.length; i < length; i++) {
                sb.append(" xx");
            }
            return sb.toString();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Entry other && protocol == other.protocol && length == other.length
                    && java.util.Arrays.equals(prefix, other.prefix) && description.equals(other.description);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(protocol, java.util.Arrays.hashCode(prefix), length, description);
        }

        @Override
        public String toString() {
            return protocol + " " + pattern() + " (" + description + ")";
        }
    }

    private static Entry entry(ApplicationProtocol protocol, String prefixHex, int length, String description) {
        return new Entry(protocol, Hex.parse(prefixHex), length, description);
    }

    /**
     * THE allowlist. Provisional, approved by the user on 2026-10-22 (minus KWP {@code 21 xx}); every entry is
     * UNCONFIRMED until checked against what VCDS sends in the capture. Do not change without the user's
     * explicit approval.
     *
     * <p>How much of each payload is checked:
     * <ul>
     *   <li>Session control and Tester Present: the sub-function byte must match exactly and nothing may
     *       follow it. So {@code 10 02} / {@code 10 85} (programming), {@code 10 81}, {@code 10 82}, a KWP
     *       {@code 10 03} and so on are all refused.</li>
     *   <li>UDS {@code 19}: the sub-function must be one of {@code 01 02 04 06}, at its ISO 14229 length
     *       ({@code 19 01 mm}, {@code 19 02 mm}, {@code 19 04 dd dd dd rr}, {@code 19 06 dd dd dd rr}). The
     *       mask/DTC/record bytes may take any value: they only select what is read.</li>
     *   <li>KWP {@code 18}, {@code 1A}, {@code 12}: any single parameter byte, and exactly one. These services
     *       have no sub-function that writes, resets or changes state; the byte only selects which status
     *       group / identification block / freeze frame is read, and which ones VCDS uses is for the capture to
     *       tell. Pinning the length stops extra bytes from reaching an ECU with nonstandard parsing. ISO 14230
     *       also defines longer forms (e.g. {@code 18 02 FF 00} with a group, {@code 12} with a record access
     *       method); they are refused until the user approves them.</li>
     *   <li>UDS {@code 22}: exactly one 2-byte data identifier, any value. Reading a DID has no side effects;
     *       several DIDs in one request are refused.</li>
     * </ul>
     */
    static final List<Entry> ALLOWLIST = List.of(
            entry(ApplicationProtocol.KWP2000, "10 89", 2, "start VW diagnostic session"),
            entry(ApplicationProtocol.KWP2000, "1A", 2, "read ECU identification"),
            entry(ApplicationProtocol.KWP2000, "18", 2, "read DTCs by status"),
            entry(ApplicationProtocol.KWP2000, "12", 2, "read freeze frame data"),
            entry(ApplicationProtocol.KWP2000, "3E", 1, "tester present"),
            entry(ApplicationProtocol.UDS, "10 01", 2, "default session"),
            entry(ApplicationProtocol.UDS, "10 03", 2, "extended diagnostic session"),
            entry(ApplicationProtocol.UDS, "3E 00", 2, "tester present"),
            entry(ApplicationProtocol.UDS, "3E 80", 2, "tester present, suppress positive response"),
            entry(ApplicationProtocol.UDS, "19 01", 3, "report number of DTCs by status mask"),
            entry(ApplicationProtocol.UDS, "19 02", 3, "report DTCs by status mask"),
            entry(ApplicationProtocol.UDS, "19 04", 6, "report DTC snapshot record by DTC number"),
            entry(ApplicationProtocol.UDS, "19 06", 6, "report DTC extended data record by DTC number"),
            entry(ApplicationProtocol.UDS, "22", 3, "read data by identifier (one DID)"));

    private RequestGuard() {
    }

    /** True if {@code payload} is on the allowlist for {@code protocol}. Never throws, never logs. */
    public static boolean isAllowed(ApplicationProtocol protocol, byte[] payload) {
        if (protocol == null || payload == null || payload.length == 0) {
            return false;
        }
        for (Entry e : ALLOWLIST) {
            if (e.matches(protocol, payload)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks one request. Allowed: returns. Otherwise logs at WARN and throws.
     *
     * @throws ForbiddenRequestException if the request is not on the allowlist
     */
    public static void check(ApplicationProtocol protocol, byte[] payload) {
        Optional<String> reason = rejectionReason(protocol, payload);
        if (reason.isPresent()) {
            ForbiddenRequestException e = new ForbiddenRequestException(protocol, payload, reason.get());
            log.warn("{}", e.getMessage());
            throw e;
        }
    }

    /** Why the request is refused, or empty if it is allowed. Pure: no logging. */
    static Optional<String> rejectionReason(ApplicationProtocol protocol, byte[] payload) {
        if (protocol == null) {
            return Optional.of("no application protocol");
        }
        if (payload == null) {
            return Optional.of("no payload");
        }
        if (payload.length == 0) {
            return Optional.of("empty payload");
        }
        if (isAllowed(protocol, payload)) {
            return Optional.empty();
        }
        int sid = Byte.toUnsignedInt(payload[0]);
        List<Entry> sameService = ALLOWLIST.stream()
                .filter(e -> e.protocol() == protocol && Byte.toUnsignedInt(e.prefix[0]) == sid)
                .toList();
        if (sameService.isEmpty()) {
            return Optional.of(String.format("service %02X is not on the read-only allowlist for %s", sid, protocol));
        }
        List<String> patterns = sameService.stream().map(Entry::pattern).toList();
        return Optional.of(String.format("only %s allowed for service %02X", String.join(" / ", patterns), sid));
    }
}
