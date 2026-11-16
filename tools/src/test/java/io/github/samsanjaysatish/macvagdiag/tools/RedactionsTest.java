package io.github.samsanjaysatish.macvagdiag.tools;

import static io.github.samsanjaysatish.macvagdiag.tools.VinTest.TEST_VIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.tools.Redactions.Hit;
import io.github.samsanjaysatish.macvagdiag.tools.Redactions.Kind;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RedactionsTest {

    private static final Redactions VIN_ONLY = Redactions.of(Optional.of(TEST_VIN), List.of());

    private static String maskBytes(Redactions r, String ascii) {
        byte[] b = ascii.getBytes(StandardCharsets.US_ASCII);
        r.maskBytes(b);
        return new String(b, StandardCharsets.US_ASCII);
    }

    @Test
    void fullVinBecomesTheReplacement() {
        assertEquals("xx" + Vin.FAKE + "yy", maskBytes(VIN_ONLY, "xx" + TEST_VIN + "yy"));
    }

    @Test
    void piecesOfEightOrMoreAreReplacedByTheFakeAtTheSameOffset() {
        // "KP042788" = last 8 (offset 9); "1M8GDM9AX" = first 9.
        assertEquals("..D0000000..", maskBytes(VIN_ONLY, "..KP042788.."));
        assertEquals("3VW00000X", maskBytes(VIN_ONLY, "1M8GDM9AX"));
        // 7 characters aren't masked (verification still finds them).
        assertEquals("GDM9AXK", maskBytes(VIN_ONLY, "GDM9AXK"));
        assertEquals(1, VIN_ONLY.findBytes("GDM9AXK".getBytes(StandardCharsets.US_ASCII)).size());
    }

    @Test
    void serialIsMaskedAnywhereInBytesButOnlyAsATokenInText() {
        assertEquals("A000000B", maskBytes(VIN_ONLY, "A042788B"));
        List<Hit> hits = new ArrayList<>();
        assertEquals("Mileage: 1042788 km, serial 000000.",
                VIN_ONLY.maskText("Mileage: 1042788 km, serial 042788.", hits));
        assertEquals(List.of(Kind.VIN_SERIAL), hits.stream().map(h -> h.needle().kind()).toList());
        assertTrue(VIN_ONLY.findText("Mileage: 1042788 km").isEmpty());
        assertEquals(1, VIN_ONLY.findText("serial 042788").size());
    }

    @Test
    void matchingIgnoresCaseAndKeepsIt() {
        assertEquals(Vin.FAKE.toLowerCase(), maskBytes(VIN_ONLY, TEST_VIN.toLowerCase()));
        List<Hit> hits = new ArrayList<>();
        assertEquals("vin " + Vin.FAKE.toLowerCase(), VIN_ONLY.maskText("vin " + TEST_VIN.toLowerCase(), hits));
    }

    @Test
    void hitsReportKindAndOffset() {
        byte[] b = ("ab" + TEST_VIN + "..042788").getBytes(StandardCharsets.US_ASCII);
        List<Hit> hits = VIN_ONLY.maskBytes(b);
        assertEquals(2, hits.size());
        assertEquals(2, hits.get(0).offset());
        assertEquals(Kind.VIN, hits.get(0).needle().kind());
        assertEquals(Kind.VIN_SERIAL, hits.get(1).needle().kind());
        assertTrue(VIN_ONLY.findBytes(b).isEmpty(), "nothing left after masking");
    }

    @Test
    void extraStringsKeepLengthAndShape() {
        Redactions r = Redactions.of(Optional.of(TEST_VIN), List.of("3572MSME-1660"));
        assertEquals("s/n 0000XXXX-0000 end", maskBytes(r, "s/n 3572MSME-1660 end"));
        assertEquals("0000XXXX-0000", Redactions.scramble("3572msme-1660"));
    }

    @Test
    void refusesInputsItCouldNotMaskSafely() {
        assertThrows(IllegalArgumentException.class, () -> Redactions.of(Optional.of("TOO-SHORT"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> Redactions.of(Optional.of(Vin.FAKE), List.of()));
        // Shares 16 characters with the fake: masking couldn't remove them.
        assertThrows(IllegalArgumentException.class,
                () -> Redactions.of(Optional.of("3VW00000XD0000001"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> Redactions.of(Optional.of(TEST_VIN), List.of("12345")));
        assertThrows(IllegalArgumentException.class, () -> Redactions.of(Optional.of(TEST_VIN), List.of("000000")));
        assertThrows(IllegalArgumentException.class,
                () -> Redactions.of(Optional.of(TEST_VIN), List.of("café123")));
    }

    @Test
    void errorMessagesNeverContainTheVin() {
        String vinLike = "3VW00000XD0000001";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Redactions.of(Optional.of(vinLike), List.of()));
        assertTrue(!e.getMessage().contains(vinLike) && !e.getMessage().contains("0000001"), e.getMessage());
    }
}
