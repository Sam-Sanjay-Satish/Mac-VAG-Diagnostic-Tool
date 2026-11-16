package io.github.samsanjaysatish.macvagdiag.diag;

import java.util.List;

/**
 * Messages copied byte for byte from the 2026-11-13 listen-only capture of the target car (VCDS Auto-Scan and
 * 01 fault-code read), reassembled by {@code local/analysis/decode.py}. Only fault-code, freeze-frame,
 * identification-block, single-DID and installation-list messages: <b>none of them holds the VIN</b> (VCDS read
 * the VIN only with OBD-II {@code 09 02}, which isn't used here). The expected values in the tests are what the
 * VCDS logs of the same session print for the same modules.
 */
final class CaptureBytes {

    private CaptureBytes() {
    }

    // ---- 01 Engine (KWP2000 over TP 2.0, tester tx 740) ----

    /** {@code 18 00 FF 00} → 12 DTCs. */
    static final String ENGINE_DTCS = "58 0C 2B A6 E0 1A 58 60 19 46 20 04 01 61 24 0F E0 06 91 20 05 44 20 04 01 22 "
            + "05 44 21 00 87 21 00 87 62 00 87 26";

    /** {@code 12 00 04 [hi] [lo]} → one answer per DTC list entry, in list order (repeats return the next record). */
    static final List<String> ENGINE_FREEZE_FRAMES = List.of(
            "52 00 6C 01 E0 02 FE FF 05 87 10 00 01 FF BE 93 5E 01 73 24 07 64 00 21 FF 00 60 79 53 05 09 73 31 C8 34 05 09 9A 04 2B A6",
            "52 00 6C 01 60 06 FF FF 05 87 10 00 01 FF BE 93 5F 01 73 24 07 64 00 21 FF 00 60 79 53 05 09 73 31 C8 34 05 09 9A 04 1A 58",
            "52 00 6C 01 20 02 FF FF 05 87 10 00 01 FF BE 93 6B 01 73 24 07 64 00 05 09 9A 05 09 CF 05 09 73 14 65 AC 14 65 80 04 19 46",
            "52 00 6C 01 61 02 FE FF 05 87 10 00 01 FF BE 93 80 01 73 23 07 64 00 06 4C B6 21 FF B7 21 FF FF 31 C8 2D 31 C8 46 04 04 01",
            "52 00 6C 01 E0 02 02 FF 05 87 17 00 01 FF BE 97 D4 01 73 40 07 64 4B 21 FF B0 31 C8 00 31 C8 1D 31 C8 37 31 C8 58 04 24 0F",
            "52 00 6C 01 20 02 42 FF 05 87 64 00 01 FF BF 22 0C 01 73 4E 07 64 27 05 09 79 05 09 B7 05 09 75 14 65 8C 14 65 80 04 06 91",
            "52 00 6C 01 20 02 0E FF 05 8D AC 00 01 FF BE C9 B8 01 73 82 07 64 5D 06 4C B6 05 09 C6 05 3C FF 05 3C BA 05 3C AE 04 05 44",
            "52 00 6C 01 22 02 07 FF 05 94 8C 00 01 FF BF 09 77 01 73 43 07 64 40 06 4C B7 21 FF ED 21 FF FF 31 C8 48 31 C8 67 04 04 01",
            "52 00 6C 01 21 02 1D FF 05 8D AC 00 01 FF BE C9 B4 01 73 4B 07 64 58 06 4C B5 05 09 CF 05 3C FF 05 3C BD 05 3C AA 04 05 44",
            "52 00 6C 01 21 02 FE FF 05 96 53 00 01 FF BE F9 A7 01 73 47 27 46 9C 08 0A 05 64 50 AB 64 50 16 05 09 A7 13 1C 0A 04 00 87",
            "52 00 6C 01 62 02 01 FF 05 96 53 00 01 FF BE F9 A8 01 73 52 27 46 5B 08 0A 05 64 50 AC 64 50 21 05 09 A7 13 1C 0A 04 00 87",
            "52 00 6C 01 26 02 01 FF 05 96 54 00 01 FF BE F9 AE 01 73 70 27 46 4A 08 0A 04 64 50 BA 64 50 0A 05 09 A7 13 1C 0A 04 00 87");

    /** {@code 1A 9B}. */
    static final String ENGINE_ID_9B = "5A 9B 30 33 4C 39 39 37 30 33 30 4D 20 20 39 39 38 33 03 00 C3 9E 00 00 00 00 00 42 "
            + "52 34 20 32 2E 30 6C 20 54 44 49 20 47 30 30 30 41 47 20 20";

    // ---- other KWP modules: 1A 9B ----

    /** 02 Auto Trans. */
    static final String TRANS_ID_9B = "5A 9B 30 32 45 33 30 30 30 36 32 42 20 20 39 34 33 30 03 00 00 14 00 00 00 00 00 42 "
            + "47 53 47 20 44 53 47 20 41 47 36 20 20 20 20 20 35 31 32 20";
    /** 08 Auto HVAC (TP 2.0 logical 2C): no coding. */
    static final String HVAC_ID_9B = "5A 9B 35 43 30 38 32 30 30 34 37 42 41 20 30 32 30 34 00 00 00 00 00 00 00 00 00 42 "
            + "4A 33 30 31 20 20 4B 6C 69 6D 61 61 6E 6C 61 67 65 20 20 20";
    /** 44 Steering Assist (TP 2.0 logical 09): no coding. */
    static final String STEERING_ID_9B = "5A 9B 31 4B 30 39 30 39 31 34 34 50 20 20 33 33 30 35 00 00 00 00 0A FF E0 00 08 15 "
            + "45 50 53 5F 5A 46 4C 53 20 4B 6C 2E 20 37 30 20 20 20 20 20";

    // ---- 19 CAN Gateway (TP 2.0 logical 1F, tester tx 32E) ----

    /** {@code 18 02 FF 00} → 01304, 02078. */
    static final String GATEWAY_DTCS = "58 02 05 18 64 08 1E 64";
    static final List<String> GATEWAY_FREEZE_FRAMES = List.of(
            "52 00 6C 01 64 02 01 F0 05 7A 8C 00 01 FF BF 20 B1 04 05 18",
            "52 00 6C 01 64 02 01 F0 05 7A 8C 00 01 FF BF 20 B2 04 08 1E");
    /** {@code 22 04 A1}: 35 entries. */
    static final String GATEWAY_INSTALLATION_LIST = "62 04 A1 "
            + "01 01 05 10 02 02 01 10 03 03 01 10 17 07 05 10 15 05 01 10 44 09 01 10 04 00 01 10 55 06 00 10 "
            + "10 1D 00 10 08 2C 01 11 3D 3A 00 11 69 43 00 11 47 53 00 11 2E 54 19 11 37 5B 00 11 57 57 00 11 "
            + "56 52 19 11 77 5A 05 11 09 20 01 15 19 1F 05 15 25 14 01 10 22 0A 00 10 4B 39 00 11 13 0B 00 10 "
            + "6E 3D 00 11 0D 24 00 11 78 25 00 11 18 2F 00 11 2D 59 00 11 6C 49 00 11 32 08 00 10 05 31 01 31 "
            + "3C 1C 00 10 26 30 00 11 2B 30 01 31";
    /** Single-DID answers, as sent by the gateway. */
    static final String GATEWAY_F187 = "62 F1 87 36 4E 30 39 30 39 39 30 31 20 20";
    static final String GATEWAY_F191 = "62 F1 91 35 43 30 39 33 37 30 38 37 41 20";
    static final String GATEWAY_F197 = "62 F1 97 47 57 2D 4B 2D 43 41 4E 20 54 50 32 30";
    static final String GATEWAY_0600 = "62 06 00 10 22 00";
    /**
     * {@code F189}: VCDS read it from the gateway only inside its 13-byte multi-DID request (not allowlisted);
     * this single-DID answer is that answer's {@code F1 89 30 32 35 37} part.
     */
    static final String GATEWAY_F189_FROM_MULTI_DID = "62 F1 89 30 32 35 37";

    // ---- UDS over ISO-TP ----

    /** 17 Instruments (714 → 77E), {@code 19 02 AE}. */
    static final String INSTRUMENTS_DTCS = "59 02 99 90 3E 12 08 90 59 1B 08";
    /** 77 Telephone (76B → 7D5), {@code 19 02 AE}. */
    static final String TELEPHONE_DTCS = "59 02 19 90 4F 13 09 90 4E 13 09 00 00 16 09";
}
