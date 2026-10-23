package io.github.samsanjaysatish.macvagdiag.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Untrusted input (MCP arguments, URL paths) is rejected before any request is built. */
class ArgumentValidationTest {

    @Test
    @DisplayName("the known set is pinned: 01 02 03 08 09 15 16 17 19 25 44 46 56, in address order")
    void knownSetIsPinned() {
        assertEquals(List.of("01", "02", "03", "08", "09", "15", "16", "17", "19", "25", "44", "46", "56"),
                ModuleAddress.known().stream().map(ModuleAddress::hex).toList());
        assertEquals("Engine", ModuleAddress.ENGINE.name());
        assertEquals("CAN Gateway", ModuleAddress.GATEWAY.name());
        // UNCONFIRMED link plan: TP 2.0 logical address = VCDS address; engine/transmission also ISO-TP.
        assertEquals(List.of(new ModuleLink.Tp20(0x01), new ModuleLink.IsoTp(0x7E0, 0x7E8, Protocol.UDS)),
                ModuleAddress.ENGINE.links());
        assertEquals(List.of(new ModuleLink.Tp20(0x17)), ModuleAddress.parse("17").links());
    }

    @ParameterizedTest
    @ValueSource(strings = {"08", "17", "01", "56"})
    void twoHexDigitsOfAKnownModuleParse(String text) {
        assertEquals(text, ModuleAddress.parse(text).hex());
    }

    @Test
    @DisplayName("hex is case-insensitive: \"1a\" and \"1A\" both parse as hex (and name no known module)")
    void caseInsensitive() {
        for (String text : new String[] {"1a", "1A", "aA"}) {
            InvalidModuleAddressException e = assertThrows(InvalidModuleAddressException.class,
                    () -> ModuleAddress.parse(text));
            assertTrue(e.getMessage().contains("is not a known module"), e.getMessage());
        }
        assertEquals(ModuleAddress.parse("46"), ModuleAddress.tryParse("46").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"8", "008", " 08", "08 ", "0x08", "+8", "-8", "", "zz", "1G", "٠٨", "０８",
            "08\n", "8\u0000", "17;rm"})
    @DisplayName("anything but exactly two ASCII hex digits is rejected")
    void malformedIsRejected(String text) {
        InvalidModuleAddressException e = assertThrows(InvalidModuleAddressException.class, () -> ModuleAddress.parse(text));
        assertTrue(e.getMessage().contains("two hex digits"), e.getMessage());
        assertTrue(e.getMessage().contains("known modules: 01, 02"), e.getMessage());
        assertFalse(ModuleAddress.tryParse(text).isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"00", "04", "0F", "18", "20", "7F", "FF"})
    @DisplayName("well-formed but unknown addresses are rejected")
    void unknownIsRejected(String text) {
        InvalidModuleAddressException e = assertThrows(InvalidModuleAddressException.class, () -> ModuleAddress.parse(text));
        assertTrue(e.getMessage().contains("not a known module"), e.getMessage());
    }

    @ParameterizedTest
    @NullSource
    void nullIsRejected(String text) {
        assertThrows(InvalidModuleAddressException.class, () -> ModuleAddress.parse(text));
    }

    @Test
    @DisplayName("ModuleAddress.of accepts only known numbers")
    void ofNumber() {
        assertEquals("17", ModuleAddress.of(0x17).hex());
        assertThrows(InvalidModuleAddressException.class, () -> ModuleAddress.of(0x99));
        assertThrows(InvalidModuleAddressException.class, () -> ModuleAddress.of(-1));
        assertThrows(InvalidModuleAddressException.class, () -> ModuleAddress.of(0x117));
    }

    @Test
    @DisplayName("a huge or control-character input is not echoed back raw in the message")
    void messagesDontEchoRawInput() {
        String evil = "\u001b[31m" + "A".repeat(10_000);
        InvalidModuleAddressException e = assertThrows(InvalidModuleAddressException.class, () -> ModuleAddress.parse(evil));
        assertTrue(e.getMessage().length() < 300, "message length " + e.getMessage().length());
        assertFalse(e.getMessage().contains("\u001b"));
    }

    @Test
    @DisplayName("an unknown module never reaches the service: no connection, no request")
    void unknownModuleNeverReachesTheCar() {
        try (TestScanServices t = TestScanServices.create()) {
            assertThrows(InvalidModuleAddressException.class,
                    () -> t.service().readFaultCodes(Caller.web(), ModuleAddress.parse("99")));
            assertEquals(List.of(), t.connector().events());
        }
    }

    @Test
    @DisplayName("link records validate their addressing")
    void linksValidate() {
        assertThrows(IllegalArgumentException.class, () -> new ModuleLink.Tp20(0x00));
        assertThrows(IllegalArgumentException.class, () -> new ModuleLink.Tp20(0x80));
        assertThrows(IllegalArgumentException.class, () -> new ModuleLink.IsoTp(0x7E0, 0x7E0, Protocol.UDS));
        assertThrows(IllegalArgumentException.class, () -> new ModuleLink.IsoTp(0x800, 0x7E8, Protocol.UDS));
        assertEquals("UDS over ISO-TP (7E0/7E8)", new ModuleLink.IsoTp(0x7E0, 0x7E8, Protocol.UDS).describe());
    }

    @Test
    @DisplayName("callers: web, cli, mcp:<tool>; tool names are validated")
    void callers() {
        assertEquals("web", Caller.web().label());
        assertEquals("cli", Caller.cli().label());
        assertEquals("mcp:read_fault_codes", Caller.mcp("read_fault_codes").label());
        for (String bad : new String[] {null, "", "Read", "read-codes", "1abc", "a b", "x".repeat(65), "tool\n"}) {
            assertThrows(IllegalArgumentException.class, () -> Caller.mcp(bad), String.valueOf(bad));
        }
    }

    @Test
    @DisplayName("tool-call records bound untrusted arguments")
    void toolCallRecordsAreBounded() {
        Map<String, String> args = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++) {
            args.put("k" + i, "v".repeat(500));
        }
        ToolCallRecord r = new ToolCallRecord("read_fault_codes", args, "ok", "s".repeat(5000));
        assertEquals(ToolCallRecord.MAX_ARGUMENTS + 1, r.arguments().size());
        assertTrue(r.arguments().get("k0").length() <= ToolCallRecord.MAX_VALUE_LENGTH + ToolCallRecord.TRUNCATED.length());
        assertTrue(r.summary().length() <= ToolCallRecord.MAX_SUMMARY_LENGTH + ToolCallRecord.TRUNCATED.length());
        assertEquals("k0", r.arguments().keySet().iterator().next(), "argument order kept");
        assertThrows(IllegalArgumentException.class, () -> new ToolCallRecord("Bad Tool", Map.of(), "ok", ""));
    }
}
