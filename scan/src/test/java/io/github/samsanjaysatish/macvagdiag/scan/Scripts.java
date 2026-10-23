package io.github.samsanjaysatish.macvagdiag.scan;

import io.github.samsanjaysatish.macvagdiag.diag.DiagResult;
import io.github.samsanjaysatish.macvagdiag.diag.Dtc;
import io.github.samsanjaysatish.macvagdiag.diag.EcuIdentification;
import io.github.samsanjaysatish.macvagdiag.diag.FakeDiagnosticService;
import io.github.samsanjaysatish.macvagdiag.diag.NegativeResponse;
import io.github.samsanjaysatish.macvagdiag.diag.RawBytes;
import io.github.samsanjaysatish.macvagdiag.diag.RawRecord;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import java.time.Duration;
import java.util.Map;

/** Small builders for scripted fake modules. */
final class Scripts {

    private Scripts() {
    }

    static <T> DiagResult<T> ok(T value) {
        return FakeDiagnosticService.ok(value);
    }

    static DiagResult<RawBytes> okHex(String hex) {
        return ok(RawBytes.ofHex(hex));
    }

    static <T> DiagResult<T> timeout() {
        return new DiagResult.TimedOut<>(Duration.ofMillis(1000), 0);
    }

    static <T> DiagResult<T> busy(int sid) {
        return new DiagResult.Busy<>(NegativeResponse.of(sid, 0x21));
    }

    static <T> DiagResult<T> negative(int sid, int nrc) {
        return new DiagResult.Negative<>(NegativeResponse.of(sid, nrc));
    }

    /** KWP module: {@code 10 89} OK, then {@code 18 02} with these DTCs. */
    static FakeDiagnosticService kwp(Dtc... dtcs) {
        return FakeDiagnosticService.kwp().onStartSession(okHex("50 89"))
                .onReadDtcs(ok(FakeDiagnosticService.dtcList(dtcs)));
    }

    /** KWP module with identification (Auto-Scan reads it). */
    static FakeDiagnosticService kwpScan(String text, Dtc... dtcs) {
        return kwp(dtcs).onReadIdentification(ok(new EcuIdentification(ApplicationProtocol.KWP2000, Map.of(),
                java.util.List.of(new RawRecord("1A 9B", new RawBytes(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))),
                java.util.List.of())));
    }

    static FakeDiagnosticService uds() {
        return FakeDiagnosticService.uds().onStartSession(okHex("50 01 00 32 01 F4"));
    }
}
