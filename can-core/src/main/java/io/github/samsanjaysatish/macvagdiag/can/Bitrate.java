package io.github.samsanjaysatish.macvagdiag.can;

/** CAN bus bitrates the adapters support. VW diagnostic CAN is expected at {@link #KBIT_500}. */
public enum Bitrate {
    KBIT_10(10_000),
    KBIT_20(20_000),
    KBIT_50(50_000),
    KBIT_100(100_000),
    KBIT_125(125_000),
    KBIT_250(250_000),
    KBIT_500(500_000),
    KBIT_1000(1_000_000);

    private final int bitsPerSecond;

    Bitrate(int bitsPerSecond) {
        this.bitsPerSecond = bitsPerSecond;
    }

    public int bitsPerSecond() {
        return bitsPerSecond;
    }

    /** The bitrate for a value in bit/s, e.g. {@code 500000}. */
    public static Bitrate ofBitsPerSecond(int bitsPerSecond) {
        for (Bitrate b : values()) {
            if (b.bitsPerSecond == bitsPerSecond) {
                return b;
            }
        }
        throw new IllegalArgumentException("Unsupported CAN bitrate: " + bitsPerSecond + " bit/s");
    }
}
