package io.github.samsanjaysatish.macvagdiag.tools;

import static io.github.samsanjaysatish.macvagdiag.tools.VinTest.TEST_VIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.tools.VcdsLogSanitizer.Field;
import io.github.samsanjaysatish.macvagdiag.tools.VcdsLogSanitizer.FieldHit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A hand-written VCDS log in the layout of a real one, every value made up. CRLF like VCDS writes it. */
class VcdsLogSanitizerTest {

    static final String LOG = ("""
            Wednesday,11,November,2026,19:31:00:00009
            VCDS -- Windows Based VAG/VAS Emulator Running on Windows 10 x64
            VCDS Version: 26.9.0.3

            VIN: 1M8GDM9AXKP042788   License Plate: ABC1234
            Mileage: 1042788km-647976mi   Repair Order:

            Chassis Type: AJ-VW36 (6N0)
            VIN: 1M8GDM9AXKP042788   Mileage: 1042788km-647976miles

            Address 01: Engine (CJA)       Labels: 03L-906-022-CBE.clb
               Part No SW: 03L 997 030 M    HW: 03L 907 309 AA
               Revision: 12H15---    Serial number: 00001234567890
               Coding: 0050078
               Shop #: WSC 01234 567 89012
               VCID: 0123456789ABCDEF01-8000
               VINID: 00112233445566778899AABBCCDDEEFF00
               Coolant: 90 °C
               Subsystem 1 - Serial number: --------------------
               Subsystem 2 - Serial number: 00001234567890 Dataset Number: 5K0116v____ 1609
            Address 17: Instruments (J285)
               Serial number: 00000000000000
            """).replace("\n", "\r\n");

    private static final Redactions VIN = Redactions.of(Optional.of(TEST_VIN), List.of());

    private static List<Field> fieldsOf(VcdsLogSanitizer.Result r, boolean masked) {
        return r.fields().stream().filter(h -> h.masked() == masked).map(FieldHit::field).toList();
    }

    @Test
    void defaultMasksTheVinAndThePlateAndKeepsTheRest() throws SanitizeException {
        VcdsLogSanitizer.Result r = VcdsLogSanitizer.sanitize(LOG, VIN, Set.of());

        String expected = LOG.replace(TEST_VIN, Vin.FAKE).replace("License Plate: ABC1234", "License Plate: XXX0000");
        assertEquals(expected, r.output());
        assertEquals(LOG.length(), r.output().length());
        assertEquals(2, r.textHits().size());
        assertEquals(List.of(5, 9), r.textHits().stream().map(VcdsLogSanitizer.TextHit::line).toList());
        assertEquals(List.of(Field.LICENSE_PLATE), fieldsOf(r, true));
        assertEquals(List.of(Field.SERIAL, Field.SHOP_CODE, Field.VCID, Field.VINID, Field.SERIAL, Field.SERIAL),
                fieldsOf(r, false));
        assertTrue(r.output().contains("1042788km"), "a mileage containing the serial's digits stays");
        assertTrue(r.output().contains("°C\r\n"), "non-ASCII bytes and CRLF stay");
        VcdsLogSanitizer.verify(LOG, r.output(), VIN, Set.of());
    }

    @Test
    void maskAllMasksEveryIdentifyingFieldEverywhere() throws SanitizeException {
        Set<Field> all = EnumSet.of(Field.VCID, Field.VINID, Field.SERIAL, Field.SHOP_CODE);
        VcdsLogSanitizer.Result r = VcdsLogSanitizer.sanitize(LOG, VIN, all);

        assertFalse(r.output().contains("00001234567890"));
        assertTrue(r.output().contains("Serial number: 00000000000000\r\n"));
        assertTrue(r.output().contains("Shop #: WSC 00000 000 00000"));
        assertTrue(r.output().contains("VCID: 000000000000000000-0000"));
        assertTrue(r.output().contains("VINID: 0000000000000000000000000000000000"));
        assertTrue(r.output().contains("Serial number: --------------------"), "empty serials stay");
        assertTrue(r.output().contains("Revision: 12H15---"), "not identifying");
        // An all-zero serial (like the real instrument cluster's) is its own mask: it must not make
        // verification see the other masked serials as leftovers.
        assertTrue(r.output().contains("   Serial number: 00000000000000\r\n"));
        assertEquals(List.of(), fieldsOf(r, false));
        assertEquals(LOG.length(), r.output().length());
        VcdsLogSanitizer.verify(LOG, r.output(), VIN, all);
    }

    @Test
    void maskedValuesAreAlsoMaskedOutsideTheirField() throws SanitizeException {
        String log = LOG + "Note: module 00001234567890 replaced\r\n";
        VcdsLogSanitizer.Result r = VcdsLogSanitizer.sanitize(log, VIN, EnumSet.of(Field.SERIAL));
        assertEquals(1, r.otherPlaces());
        assertTrue(r.output().contains("Note: module 00000000000000 replaced"));
        VcdsLogSanitizer.verify(log, r.output(), VIN, EnumSet.of(Field.SERIAL));
    }

    @Test
    void verifyFailsOnAnythingLeft() {
        SanitizeException e = assertThrows(SanitizeException.class,
                () -> VcdsLogSanitizer.verify(LOG, LOG, VIN, Set.of()));
        assertTrue(e.getMessage().contains("line 5: VIN remains"), e.getMessage());
        assertTrue(e.getMessage().contains("License Plate value remains"), e.getMessage());
        assertFalse(e.getMessage().contains(TEST_VIN));
        assertFalse(e.getMessage().contains("ABC1234"));

        String vinMasked = LOG.replace(TEST_VIN, Vin.FAKE).replace("ABC1234", "XXX0000");
        assertThrows(SanitizeException.class, () -> VcdsLogSanitizer.verify(LOG, vinMasked, VIN,
                EnumSet.of(Field.SHOP_CODE)), "workshop code still there");
        assertThrows(SanitizeException.class, () -> VcdsLogSanitizer.verify(LOG, vinMasked + "x", VIN, Set.of()),
                "length changed");
    }

    @Test
    void findsTheVinInTheLog() {
        assertEquals(Set.of(TEST_VIN), VinFinder.inVcdsLog(LOG));
        assertEquals(Set.of(), VinFinder.inVcdsLog("Address 01: Engine\r\n"));
    }
}
