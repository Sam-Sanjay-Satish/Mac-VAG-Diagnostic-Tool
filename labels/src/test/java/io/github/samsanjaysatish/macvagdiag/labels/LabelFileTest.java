package io.github.samsanjaysatish.macvagdiag.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Parsing one file: what is kept (names, specs), what never is (basic settings, adaptation, coding, help). */
class LabelFileTest {

    private static MeasuringLabels engine() throws Exception {
        return LabelLibraryTest.main().lookup(0x01, "03L997030M", Optional.empty()).labels().orElseThrow();
    }

    @Test
    @DisplayName("names, details and only the Range / Specification help lines; Windows-1252 degree sign")
    void namesAndSpecs() throws Exception {
        GroupLabel g = engine().group(1).orElseThrow();
        assertEquals("Basic Values", g.title());
        assertEquals(4, g.fields().size());
        FieldLabel rpm = g.field(1).orElseThrow();
        assertEquals("Engine Speed", rpm.name());
        assertEquals("(G28)", rpm.detail());
        assertEquals("Engine Speed (G28)", rpm.displayName());
        assertEquals(List.of("Range: 0...6000 /min", "Specification (Idle): 800...900 /min"), rpm.specs());
        assertEquals("Fuel Quantity", g.field(2).orElseThrow().displayName());
        assertEquals(List.of("Range: -40.0...+140.0 °C", "Specification (Warm): 80.0...105.0 °C"),
                g.field(4).orElseThrow().specs());
    }

    @Test
    @DisplayName("other help text (bit tables) is dropped; missing fields are absent")
    void helpDropped() throws Exception {
        GroupLabel g = engine().group(2).orElseThrow();
        assertEquals(List.of(1, 3), g.fields().stream().map(FieldLabel::field).toList());
        assertEquals(List.of(), g.field(3).orElseThrow().specs());
    }

    @Test
    @DisplayName("only measuring lines become groups: no basic settings, adaptation, coding, security, output tests")
    void onlyMeasuringGroups() throws Exception {
        MeasuringLabels m = engine();
        assertEquals(Set.of(1, 2, 7, 8, 82, 83, 99, 109, 120), m.groups().keySet());
        assertFalse(m.groups().containsKey(3), "B003 is basic settings");
        assertFalse(m.groups().containsKey(50), "A050 is adaptation");
        assertFalse(m.groups().containsKey(0));
        for (GroupLabel g : m.groups().values()) {
            for (FieldLabel f : g.fields()) {
                assertFalse(f.displayName().contains("Basic Setting") || f.displayName().contains("pedals"), f.toString());
            }
            assertFalse(g.title().contains("Basic Setting") || g.title().contains("Adaptation")
                    || g.title().contains("Coding") || g.title().contains("Security") || g.title().contains("Output"));
        }
    }

    @Test
    @DisplayName("odd shapes: fields above 4 ignored, short group numbers, 4-digit group, quotes")
    void oddShapes() throws Exception {
        MeasuringLabels m = engine();
        assertEquals(List.of(), m.group(99).orElseThrow().fields());
        assertEquals("Duplicate Group Written Short", m.group(8).orElseThrow().title(),
                "'8,0' is group 008: its title joins the field listed earlier");
        assertEquals("Field Without Title", m.group(8).orElseThrow().field(1).orElseThrow().name());
        assertEquals("Four Digit Group", m.group(120).orElseThrow().title());
        FieldLabel q = m.group(109).orElseThrow().field(2).orElseThrow();
        assertEquals("Ratio", q.name());
        assertEquals("\"D/S\" Mode", q.detail());
    }

    @Test
    @DisplayName("privacy: VIN and key-ID groups withheld (numbers only); immobilizer as a CAN node and key counts stay")
    void privacy() throws Exception {
        MeasuringLabels m = engine();
        assertEquals(Set.of(81, 84), m.withheldGroups());
        assertFalse(m.groups().containsKey(81));
        assertTrue(m.groups().containsKey(82), "CAN communication listing the immobilizer is not an identity");
        assertTrue(m.groups().containsKey(83), "a key number (slot) is not an identity");
        for (String withheld : List.of("Vehicle Ident. Number (VIN)", "Immobilizer Ident. (IMMO-ID)", "VIN",
                "Immobilizer ID", "IMMO-ID", "Kessy Key Identity via CAN", "Key-ID authorized", "WFS-ID",
                "Vehicle Identification Number")) {
            assertTrue(PrivacyFilter.WITHHELD.matcher(withheld).find(), withheld);
        }
        for (String kept : List.of("Immobilizer", "Key Number", "Number of adapted Keys", "Key Status (Transponder)",
                "Engine Speed", "Vineyard", "Coolant Temperature (G62)")) {
            assertFalse(PrivacyFilter.WITHHELD.matcher(kept).find(), kept);
        }
    }

    @Test
    @DisplayName("redirect lines: suffix list, pattern, HW pattern; bare and unknown-qualifier lines skipped; ; comments")
    void redirects() {
        LabelFile f = LabelFile.parse("x.lbl", String.join("\r\n",
                "REDIRECT,A.LBL,AF,AQ   ; comment, with comma",
                "REDIRECT,B.LBL,1J0-919-???-???",
                "REDIRECT,C.LBL,5C0-937-08?-???,HW",
                "REDIRECT,D.LBL",
                "REDIRECT,E.LBL,6R0-959-801-???,SL",
                "redirect,F.LBL,ZZ",
                "REDIRECT,,AF"));
        assertEquals(List.of("A.LBL", "B.LBL", "C.LBL", "F.LBL"),
                f.redirects().stream().map(LabelFile.Redirect::target).toList());
        assertEquals(List.of("AF", "AQ"), f.redirects().getFirst().suffixes());
        assertTrue(f.redirects().get(2).hardware());
    }
}
