package io.github.samsanjaysatish.macvagdiag.scan;

import io.github.samsanjaysatish.macvagdiag.diag.DiagResult;
import io.github.samsanjaysatish.macvagdiag.diag.Dtc;
import io.github.samsanjaysatish.macvagdiag.diag.DtcList;
import io.github.samsanjaysatish.macvagdiag.diag.EcuIdentification;
import io.github.samsanjaysatish.macvagdiag.diag.FreezeFrame;
import io.github.samsanjaysatish.macvagdiag.diag.KwpDtc;
import io.github.samsanjaysatish.macvagdiag.diag.RawBytes;
import io.github.samsanjaysatish.macvagdiag.diag.RawRecord;
import io.github.samsanjaysatish.macvagdiag.diag.UdsDtc;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import io.github.samsanjaysatish.macvagdiag.transport.Response;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** Converts diag results into scan's model types, so front ends never see diag. */
final class DiagMapper {

    private DiagMapper() {
    }

    static Protocol protocol(ApplicationProtocol p) {
        return switch (p) {
            case KWP2000 -> Protocol.KWP2000;
            case UDS -> Protocol.UDS;
        };
    }

    static RawValue raw(RawRecord r) {
        return new RawValue(r.id(), r.data().hex(), r.data().asciiText());
    }

    static EcuInfo ecuInfo(EcuIdentification id) {
        return new EcuInfo(protocol(id.protocol()), id.partNumber(), id.softwareVersion(), id.hardwareNumber(),
                id.systemName(), id.coding(), id.vin(), id.records().stream().map(DiagMapper::raw).toList(), id.notes());
    }

    static FaultCode faultCode(Dtc dtc, FreezeFrameData freezeFrame) {
        int status = dtc.status().raw();
        return switch (dtc) {
            case KwpDtc k -> new FaultCode(k.displayCode(), Protocol.KWP2000, k.record().hex(), status,
                    String.format("%02X: symptom %d, %s (KWP status layout UNCONFIRMED)", status,
                            k.status().symptomCode(), k.status().intermittent() ? "intermittent" : "static"),
                    k.status().flagNames(), k.obdCode(), OptionalInt.empty(), freezeFrame);
            case UdsDtc u -> new FaultCode(u.displayCode(), Protocol.UDS, u.record().hex(), status,
                    String.format("%02X: %s", status, u.status().flagNames().isEmpty() ? "no flags set"
                            : String.join(", ", u.status().flagNames())),
                    u.status().flagNames(), Optional.empty(), OptionalInt.of(u.failureType()), freezeFrame);
        };
    }

    static FreezeFrameData freezeFrame(DiagResult<FreezeFrame> r) {
        return switch (r) {
            case DiagResult.Success<FreezeFrame> s -> new FreezeFrameData(FreezeFrameData.Availability.READ, "read",
                    s.value().records().stream().map(DiagMapper::raw).toList(),
                    s.value().fields().stream().map(f -> new FreezeFrameData.Field(f.kind().name(), f.value(),
                            f.sourceRecordId())).toList(),
                    s.value().responses().stream().map(RawBytes::hex).toList(), s.value().notes());
            case DiagResult.NotAvailable<FreezeFrame> n -> new FreezeFrameData(FreezeFrameData.Availability.NOT_AVAILABLE,
                    n.reason(), List.of(), List.of(), List.of(), List.of());
            default -> new FreezeFrameData(FreezeFrameData.Availability.FAILED, r.message(), List.of(), List.of(),
                    responses(r), List.of());
        };
    }

    /** Short outcome name for the audit log. */
    static String outcome(DiagResult<?> r) {
        return switch (r) {
            case DiagResult.Success<?> s -> "success";
            case DiagResult.Negative<?> n -> "negative";
            case DiagResult.Busy<?> b -> "busy";
            case DiagResult.TimedOut<?> t -> "timeout";
            case DiagResult.PendingLimitExceeded<?> p -> "pending-limit";
            case DiagResult.TransportFailure<?> f -> "transport-failure";
            case DiagResult.Malformed<?> m -> "malformed";
            case DiagResult.NotAvailable<?> n -> "not-available";
        };
    }

    /** The NRC as hex, for negative and busy answers. */
    static Optional<String> nrc(DiagResult<?> r) {
        return switch (r) {
            case DiagResult.Negative<?> n -> Optional.of(String.format("%02X", n.response().code()));
            case DiagResult.Busy<?> b -> Optional.of(String.format("%02X", b.response().code()));
            default -> Optional.empty();
        };
    }

    /** The response bytes the result keeps, as bus-style hex (positive answers, NRCs, malformed answers). */
    static List<String> responses(DiagResult<?> r) {
        return switch (r) {
            case DiagResult.Success<?> s -> switch (s.value()) {
                case RawBytes b -> List.of(b.hex());
                case DtcList d -> List.of(d.response().hex());
                case EcuIdentification e -> e.records().stream().map(rec -> rec.id() + ": " + rec.data().hex()).toList();
                case FreezeFrame f -> f.responses().stream().map(RawBytes::hex).toList();
                default -> List.of();
            };
            case DiagResult.Negative<?> n -> List.of(n.response().raw().hex());
            case DiagResult.Busy<?> b -> List.of(b.response().raw().hex());
            case DiagResult.Malformed<?> m -> List.of(m.response().hex());
            default -> List.of();
        };
    }

    /** The module said something (positive, negative, busy, malformed), or nothing was asked (not available). */
    static boolean answered(DiagResult<?> r) {
        return !isFailure(r);
    }

    /** No usable answer: timeout, endless response pending, or the transport failed. */
    static boolean isFailure(DiagResult<?> r) {
        return r instanceof DiagResult.TimedOut<?> || r instanceof DiagResult.PendingLimitExceeded<?>
                || r instanceof DiagResult.TransportFailure<?>;
    }

    static boolean isAdapterFailure(DiagResult<?> r) {
        return r instanceof DiagResult.TransportFailure<?> f && f.kind() == Response.Failed.Kind.ADAPTER_ERROR;
    }
}
