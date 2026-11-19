package io.github.samsanjaysatish.macvagdiag.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.can.Hex;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import io.github.samsanjaysatish.macvagdiag.transport.FakeDiagnosticChannel;
import io.github.samsanjaysatish.macvagdiag.transport.RequestGuard;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * diag CLAUDE.md: "every request this layer can produce passes the transport RequestGuard". Every
 * {@link DiagRequest} factory is enumerated (reflection pins the set), every parameter value is swept through
 * {@link RequestGuard#isAllowed} unchanged, and a full run of both services sends only those requests.
 */
class RequestAllowlistTest {

    /** Every factory, and every request it can build. Adding a factory fails {@link #factorySetIsPinned} until listed here. */
    private static Map<String, Supplier<List<DiagRequest>>> everyRequest() {
        Map<String, Supplier<List<DiagRequest>>> m = new LinkedHashMap<>();
        m.put("kwpStartVwDiagnosticSession", () -> List.of(DiagRequest.kwpStartVwDiagnosticSession()));
        m.put("kwpTesterPresent", () -> List.of(DiagRequest.kwpTesterPresent()));
        m.put("kwpReadEcuIdentification", () -> allBytes(DiagRequest::kwpReadEcuIdentification));
        m.put("kwpReadDtcsByStatus", () -> Arrays.stream(KwpOptions.DtcRequest.values())
                .map(DiagRequest::kwpReadDtcsByStatus).toList());
        m.put("kwpReadFreezeFrameByDtc", () -> all16(DiagRequest::kwpReadFreezeFrameByDtc));
        m.put("kwpReadDataByIdentifier", () -> all16(DiagRequest::kwpReadDataByIdentifier));
        m.put("kwpReadMeasuringBlock", () -> {
            List<DiagRequest> all = new ArrayList<>();
            for (int group = 1; group <= 0xFF; group++) {
                all.add(DiagRequest.kwpReadMeasuringBlock(group));
            }
            return all;
        });
        m.put("udsStartSession", () -> List.of(DiagRequest.udsStartSession(DiagnosticSession.UDS_DEFAULT),
                DiagRequest.udsStartSession(DiagnosticSession.UDS_EXTENDED)));
        m.put("udsTesterPresent", () -> List.of(DiagRequest.udsTesterPresent()));
        m.put("udsReadDtcsByStatusMask", () -> allBytes(DiagRequest::udsReadDtcsByStatusMask));
        m.put("udsReadSnapshot", () -> byDtc(true));
        m.put("udsReadExtendedData", () -> byDtc(false));
        m.put("udsReadDataByIdentifier", () -> all16(DiagRequest::udsReadDataByIdentifier));
        return m;
    }

    private static List<DiagRequest> all16(java.util.function.IntFunction<DiagRequest> f) {
        List<DiagRequest> all = new ArrayList<>();
        for (int v = 0; v <= 0xFFFF; v++) {
            all.add(f.apply(v));
        }
        return all;
    }

    private static List<DiagRequest> allBytes(java.util.function.IntFunction<DiagRequest> f) {
        List<DiagRequest> all = new ArrayList<>();
        for (int b = 0; b <= 0xFF; b++) {
            all.add(f.apply(b));
        }
        return all;
    }

    private static final int[] SAMPLE_DTCS = {0x000000, 0x000001, 0x040100, 0x7FFFFF, 0x800000, 0xC10087, 0xFFFFFF};

    private static List<DiagRequest> byDtc(boolean snapshot) {
        List<DiagRequest> all = new ArrayList<>();
        for (int dtc : SAMPLE_DTCS) {
            for (int rec = 0; rec <= 0xFF; rec++) {
                all.add(snapshot ? DiagRequest.udsReadSnapshot(dtc, rec) : DiagRequest.udsReadExtendedData(dtc, rec));
            }
        }
        for (int i = 0; i < 2000; i++) { // spread over the whole 24-bit range
            int dtc = (int) ((i * 0x2F1B3L) & 0xFFFFFF);
            all.add(snapshot ? DiagRequest.udsReadSnapshot(dtc, i & 0xFF) : DiagRequest.udsReadExtendedData(dtc, i & 0xFF));
        }
        return all;
    }

    @Test
    @DisplayName("the DiagRequest factories are exactly the enumerated set, and the constructor is private")
    void factorySetIsPinned() {
        Set<String> factories = Arrays.stream(DiagRequest.class.getDeclaredMethods())
                .filter(m -> Modifier.isStatic(m.getModifiers()) && !Modifier.isPrivate(m.getModifiers()))
                .filter(m -> m.getReturnType() == DiagRequest.class)
                .map(Method::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(new TreeSet<>(everyRequest().keySet()), factories);
        for (Constructor<?> c : DiagRequest.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(c.getModifiers()), c.toString());
        }
        assertFalse(Modifier.isPublic(DiagRequest.class.getModifiers()), "nothing above diag can build requests");
        assertTrue(Modifier.isFinal(DiagRequest.class.getModifiers()));
    }

    @Test
    @DisplayName("every request every factory can build passes RequestGuard.isAllowed for its protocol")
    void everyRequestIsAllowed() {
        int count = 0;
        for (Map.Entry<String, Supplier<List<DiagRequest>>> e : everyRequest().entrySet()) {
            for (DiagRequest r : e.getValue().get()) {
                assertTrue(RequestGuard.isAllowed(r.protocol(), r.bytes()), e.getKey() + ": " + r);
                count++;
            }
        }
        assertTrue(count > 200_000, "swept " + count);
    }

    @Test
    @DisplayName("shapes: KWP 18 00 FF 00 / 18 02 FF 00, 12 00 04 hi lo, 22 hi lo; 1A xx 2 bytes, KWP 3E 1 byte, UDS 22 one DID")
    void shapes() {
        assertEquals("18 00 FF 00", DiagRequest.kwpReadDtcsByStatus(KwpOptions.DtcRequest.STATUS_00).hex());
        assertEquals("18 02 FF 00", DiagRequest.kwpReadDtcsByStatus(KwpOptions.DtcRequest.STATUS_02).hex());
        assertEquals("12 00 04 2B A6", DiagRequest.kwpReadFreezeFrameByDtc(0x2BA6).hex());
        assertEquals("22 04 A1", DiagRequest.kwpReadDataByIdentifier(0x04A1).hex());
        assertEquals(ApplicationProtocol.KWP2000, DiagRequest.kwpReadDataByIdentifier(0x04A1).protocol());
        assertEquals("1A 9B", DiagRequest.kwpReadEcuIdentification(0x9B).hex());
        assertEquals("21 07", DiagRequest.kwpReadMeasuringBlock(7).hex());
        assertEquals("21 FF", DiagRequest.kwpReadMeasuringBlock(255).hex());
        assertEquals("10 89", DiagRequest.kwpStartVwDiagnosticSession().hex());
        assertEquals("3E", DiagRequest.kwpTesterPresent().hex());
        assertEquals("10 01", DiagRequest.udsStartSession(DiagnosticSession.UDS_DEFAULT).hex());
        assertEquals("10 03", DiagRequest.udsStartSession(DiagnosticSession.UDS_EXTENDED).hex());
        assertEquals("3E 00", DiagRequest.udsTesterPresent().hex());
        assertEquals("19 02 FF", DiagRequest.udsReadDtcsByStatusMask(0xFF).hex());
        assertEquals("19 04 04 01 00 FF", DiagRequest.udsReadSnapshot(0x040100, 0xFF).hex());
        assertEquals("19 06 04 01 00 FF", DiagRequest.udsReadExtendedData(0x040100, 0xFF).hex());
        assertEquals("22 F1 90", DiagRequest.udsReadDataByIdentifier(0xF190).hex());
        assertEquals(ApplicationProtocol.KWP2000, DiagRequest.kwpTesterPresent().protocol());
        assertEquals(ApplicationProtocol.UDS, DiagRequest.udsTesterPresent().protocol());
    }

    @Test
    @DisplayName("out-of-range parameters and KWP 10 89 as a UDS session are refused before any bytes exist")
    void parameterRanges() {
        assertThrows(IllegalArgumentException.class, () -> DiagRequest.kwpReadFreezeFrameByDtc(0x10000));
        assertThrows(IllegalArgumentException.class, () -> DiagRequest.kwpReadFreezeFrameByDtc(-1));
        assertThrows(IllegalArgumentException.class, () -> DiagRequest.kwpReadDataByIdentifier(0x10000));
        assertThrows(IllegalArgumentException.class, () -> DiagRequest.kwpReadMeasuringBlock(0), "21 00 is never built");
        assertThrows(IllegalArgumentException.class, () -> DiagRequest.kwpReadMeasuringBlock(0x100));
        assertThrows(IllegalArgumentException.class, () -> DiagRequest.kwpReadMeasuringBlock(-1));
        assertThrows(IllegalArgumentException.class, () -> DiagRequest.kwpReadEcuIdentification(-1));
        assertThrows(IllegalArgumentException.class, () -> DiagRequest.udsReadDtcsByStatusMask(0x1FF));
        assertThrows(IllegalArgumentException.class, () -> DiagRequest.udsReadSnapshot(0x1000000, 0));
        assertThrows(IllegalArgumentException.class, () -> DiagRequest.udsReadExtendedData(0, 0x100));
        assertThrows(IllegalArgumentException.class, () -> DiagRequest.udsReadDataByIdentifier(0x10000));
        assertThrows(IllegalArgumentException.class, () -> DiagRequest.udsStartSession(DiagnosticSession.KWP_VW_DIAGNOSTIC));
    }

    @Test
    @DisplayName("bytes() is a copy: changing it can't change the checked request")
    void bytesAreCopied() {
        DiagRequest r = DiagRequest.udsReadDataByIdentifier(0xF190);
        byte[] b = r.bytes();
        b[0] = 0x2E; // write DID
        assertEquals("22 F1 90", r.hex());
    }

    @Test
    @DisplayName("a full run of every operation on both services sends only allowlisted requests (fake applies the guard)")
    void servicesSendOnlyAllowlisted() throws InterruptedException {
        FakeTimeSource time = new FakeTimeSource();
        FakeDiagnosticChannel kwp = FakeDiagnosticChannel.kwp()
                .on("10 89", "50 89").on("3E", "7E").on("1A 9B", "5A 9B 00")
                .on("1A 9B", "7F 1A 11");
        for (int did : KwpDiagnosticService.FALLBACK_DIDS.keySet()) {
            kwp.on(Hex.format(new byte[] {0x22, (byte) (did >> 8), (byte) did}), "7F 22 31");
        }
        kwp.on("18 02 FF 00", "58 01 41 2C A3").on("12 00 04 41 2C", "7F 12 31").on("22 04 A1", "62 04 A1")
                .on("18 00 FF 00", "58 00").on("22 22 03", "7F 22 31")
                .on("21 07", "61 07 05 09 8F 25 00 00 05 09 83 05 09 AF");
        KwpDiagnosticService k = new KwpDiagnosticService(kwp, time);
        k.startSession(DiagnosticSession.KWP_VW_DIAGNOSTIC);
        k.testerPresent();
        k.readIdentification();
        k.readIdentification(); // the 22 path
        DtcList kd = k.readDtcs().valueIfSuccess().orElseThrow();
        k.readFreezeFrame(kd.dtcs().getFirst());
        k.readInstallationList();
        k.readOdometer();
        k.readMeasuringBlock(7);
        new KwpDiagnosticService(kwp, time, DiagTimings.DEFAULTS, KwpOptions.ENGINE).readDtcs();

        FakeDiagnosticChannel uds = FakeDiagnosticChannel.uds()
                .on("10 03", "50 03 00 32 01 F4").on("10 01", "50 01 00 32 01 F4").on("3E 00", "7E 00")
                .on("19 02 AE", "59 02 FF 04 01 00 2F")
                .on("19 06 04 01 00 FF", "7F 19 31").on("22 04 A1", "7F 22 31").on("22 22 03", "62 22 03 8F 14");
        for (int did : UdsDiagnosticService.IDENTIFICATION_DIDS.keySet()) {
            uds.on(Hex.format(new byte[] {0x22, (byte) (did >> 8), (byte) did}), "7F 22 31");
        }
        UdsDiagnosticService u = new UdsDiagnosticService(uds, time);
        u.startSession(DiagnosticSession.UDS_EXTENDED);
        u.startSession(DiagnosticSession.UDS_DEFAULT);
        u.testerPresent();
        u.readIdentification();
        DtcList ud = u.readDtcs().valueIfSuccess().orElseThrow();
        u.readFreezeFrame(ud.dtcs().getFirst());
        u.readInstallationList();
        u.readOdometer();
        u.readMeasuringBlock(7); // UDS: NotAvailable, nothing sent

        assertEquals(List.of("10 89", "3E", "1A 9B", "1A 9B", "22 F1 87", "22 F1 89", "22 F1 91", "22 F1 97",
                "22 06 00", "18 02 FF 00", "12 00 04 41 2C", "22 04 A1", "22 22 03", "21 07", "18 00 FF 00"), kwp.requestsHex());
        assertEquals(List.of("10 03", "10 01", "3E 00", "22 F1 87", "22 F1 89", "22 F1 91", "22 F1 97", "22 06 00",
                "19 02 AE", "19 06 04 01 00 FF", "22 04 A1", "22 22 03"), uds.requestsHex());
        for (String hex : List.of(kwp.requestsHex(), uds.requestsHex()).stream().flatMap(List::stream).toList()) {
            assertTrue(!hex.equals("1A 90") && !hex.equals("22 F1 90"), "the VIN is never read: " + hex);
        }
        for (String hex : kwp.requestsHex()) {
            assertTrue(RequestGuard.isAllowed(ApplicationProtocol.KWP2000, Hex.parse(hex)), hex);
        }
        for (String hex : uds.requestsHex()) {
            assertTrue(RequestGuard.isAllowed(ApplicationProtocol.UDS, Hex.parse(hex)), hex);
        }
        assertTrue(kwp.allScriptUsed());
        assertTrue(uds.allScriptUsed());
    }
}
