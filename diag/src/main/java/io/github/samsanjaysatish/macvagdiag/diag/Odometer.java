package io.github.samsanjaysatish.macvagdiag.diag;

import java.util.Objects;

/**
 * The odometer reading, {@code 22 22 03} → {@code 62 22 03 [hi] [lo]}: a 16-bit big-endian count of 10 km.
 *
 * <p>From the 2026-11-13 capture: VCDS sent {@code 22 22 03} to 17 Instruments (UDS, {@code 714 → 77E}), which
 * answered {@code 62 22 03 8F 14}; {@code 0x8F14} = 36628, × 10 = 366280 km, exactly the "Mileage: 366280km" in
 * VCDS's report header. UNCONFIRMED: the 10 km unit rests on that one sample, and it is unknown whether other
 * modules have the DID (on the capture only 17 was asked).
 *
 * @param kilometres the reading in km (a multiple of 10)
 * @param response the whole positive response, as received
 */
public record Odometer(int kilometres, RawBytes response) {

    /** The DID: {@code 22 03}. */
    public static final int DID = 0x2203;
    /** One count is 10 km (UNCONFIRMED, see above). */
    public static final int KILOMETRES_PER_COUNT = 10;
    /** {@code 62 22 03 [hi] [lo]}. */
    static final int RESPONSE_LENGTH = 5;

    public Odometer {
        if (kilometres < 0) {
            throw new IllegalArgumentException("Negative odometer reading: " + kilometres);
        }
        Objects.requireNonNull(response, "response");
    }

    /** Parses the whole positive response {@code 62 22 03 [hi] [lo]}; anything else is {@code Malformed}. */
    static DiagResult<Odometer> parse(RawBytes response) {
        if (response.length() != RESPONSE_LENGTH || response.byteAt(1) != DID >> 8 || response.byteAt(2) != (DID & 0xFF)) {
            return new DiagResult.Malformed<>("not '62 22 03 [hi] [lo]'", response);
        }
        int count = (response.byteAt(3) << 8) | response.byteAt(4);
        return new DiagResult.Success<>(new Odometer(count * KILOMETRES_PER_COUNT, response));
    }
}
