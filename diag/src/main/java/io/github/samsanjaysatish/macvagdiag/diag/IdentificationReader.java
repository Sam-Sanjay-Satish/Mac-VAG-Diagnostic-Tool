package io.github.samsanjaysatish.macvagdiag.diag;

import io.github.samsanjaysatish.macvagdiag.can.Hex;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Collects one {@link EcuIdentification} from several requests, with the rules every multi-request operation
 * follows ({@link Reads#partFailureContinues}): a negative or malformed part is noted and the next part is tried;
 * anything else stops the operation. Shared by both services; package-private.
 */
final class IdentificationReader {

    static final int DID_HEADER_LENGTH = 3; // 62 [DID hi] [DID lo]

    private final Exchange exchange;
    final Map<EcuIdentification.Field, String> fields = new EnumMap<>(EcuIdentification.Field.class);
    final List<RawRecord> records = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    private DiagResult<EcuIdentification> firstFailure;
    private DiagResult<EcuIdentification> stop;

    IdentificationReader(Exchange exchange) {
        this.exchange = exchange;
    }

    /**
     * Sends {@code request}. Returns the positive response, or null if there is none: then the failure is noted
     * (negative / malformed, go on) or {@link #stopped()} is set (stop now).
     */
    RawBytes run(DiagRequest request) throws InterruptedException {
        DiagResult<RawBytes> r = exchange.run(request);
        if (r instanceof DiagResult.Success<RawBytes> s) {
            return s.value();
        }
        if (Reads.partFailureContinues(r)) {
            notes.add(request.hex() + ": " + r.message());
            if (firstFailure == null) {
                firstFailure = r.failure();
            }
        } else {
            stop = r.failure();
        }
        return null;
    }

    /** True once a part failed in a way that stops the whole operation. */
    boolean stopped() {
        return stop != null;
    }

    /** {@code 22 [DID]} via {@code request}, decoding the data into {@code field} (null: keep raw only). */
    void readDid(DiagRequest request, int did, EcuIdentification.Field field) throws InterruptedException {
        RawBytes response = run(request);
        if (response == null) {
            return;
        }
        if (!Reads.echoes(response, 1, did >> 8) || !Reads.echoes(response, 2, did & 0xFF)) {
            records.add(new RawRecord(request.hex() + " (whole response, DID not echoed)", response));
            notes.add(request.hex() + ": answer doesn't echo the DID, kept raw");
            return;
        }
        RawBytes data = RawBytes.slice(response.bytes(), DID_HEADER_LENGTH, response.length());
        records.add(new RawRecord(request.hex(), data));
        if (field != null) {
            decodeDidField(field, data, request);
        }
    }

    private void decodeDidField(EcuIdentification.Field field, RawBytes data, DiagRequest request) {
        switch (field) {
            case CODING -> {
                if (data.isEmpty()) {
                    notes.add(request.hex() + ": empty coding");
                } else {
                    fields.put(field, Hex.formatCompact(data.bytes()));
                }
            }
            default -> putText(field, data, request);
        }
    }

    void putText(EcuIdentification.Field field, RawBytes data, DiagRequest request) {
        data.asciiText().ifPresentOrElse(
                text -> fields.put(field, text),
                () -> notes.add(request.hex() + ": not text, kept raw"));
    }

    /** The stop result if stopped; else the identification, or the first failure if nothing was answered. */
    DiagResult<EcuIdentification> result(ApplicationProtocol protocol) {
        if (stop != null) {
            return stop;
        }
        if (records.isEmpty()) {
            return firstFailure;
        }
        return new DiagResult.Success<>(new EcuIdentification(protocol, fields, records, notes));
    }
}
