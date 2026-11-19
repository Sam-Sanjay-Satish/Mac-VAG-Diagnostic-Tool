package io.github.samsanjaysatish.macvagdiag.diag;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One KWP2000 measuring block: {@code 21 gg} → {@code 61 gg} followed by {@code [formula] [a] [b]} triplets.
 *
 * <p>From the 2026-11-18 capture of the target car's engine (groups 001, 007, 011, 033, 099, 100, checked against
 * VCDS's CSV): the module sends 8 triplets (26 bytes), and <b>only triplets 1–4 are VCDS's fields 1–4</b>.
 * Triplets 5–8 are not shown by VCDS ({@code 25 00 00}, {@code 30 00 00}, and on group 001 values like
 * {@code 25 02 7A}); they are kept raw in {@link #extraTriplets}.
 *
 * @param group the group number, 1..255 (VCDS shows it in decimal: group 7 = {@code 007})
 * @param fields fields 1..4 (fewer only if the module sent fewer triplets)
 * @param extraTriplets triplets 5 and on, raw, e.g. {@code 30 00 00}
 * @param response the whole positive response, as received
 */
public record MeasuringBlock(int group, List<MeasuringValue> fields, List<String> extraTriplets, RawBytes response) {

    /** VCDS shows four fields per group. */
    public static final int FIELDS = 4;
    /** {@code 61 gg}. */
    static final int HEADER_LENGTH = 2;
    static final int TRIPLET_LENGTH = 3;

    public MeasuringBlock {
        if (group < 1 || group > 0xFF) {
            throw new IllegalArgumentException("group must be 1..255: " + group);
        }
        fields = List.copyOf(fields);
        extraTriplets = List.copyOf(extraTriplets);
        Objects.requireNonNull(response, "response");
    }

    /** Field {@code n} (1-based), if the module sent it. */
    public java.util.Optional<MeasuringValue> field(int n) {
        return fields.stream().filter(f -> f.field() == n).findFirst();
    }

    /**
     * Parses the whole positive response for {@code 21 [group]}. Not {@code 61 [group]} + whole triplets, or no
     * triplet at all → {@code Malformed} (the response is kept).
     */
    static DiagResult<MeasuringBlock> parse(int group, RawBytes response) {
        if (response.length() < HEADER_LENGTH || response.byteAt(1) != group) {
            return new DiagResult.Malformed<>(String.format("not '61 %02X ...'", group), response);
        }
        int body = response.length() - HEADER_LENGTH;
        if (body == 0 || body % TRIPLET_LENGTH != 0) {
            return new DiagResult.Malformed<>("measuring block is not a whole number of [formula] [a] [b] triplets ("
                    + body + " bytes after 61 gg)", response);
        }
        List<MeasuringValue> fields = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        for (int i = 0; i < body / TRIPLET_LENGTH; i++) {
            int at = HEADER_LENGTH + i * TRIPLET_LENGTH;
            int formula = response.byteAt(at);
            int a = response.byteAt(at + 1);
            int b = response.byteAt(at + 2);
            if (i < FIELDS) {
                fields.add(MeasuringValue.of(i + 1, formula, a, b));
            } else {
                extra.add(String.format("%02X %02X %02X", formula, a, b));
            }
        }
        return new DiagResult.Success<>(new MeasuringBlock(group, fields, extra, response));
    }
}
