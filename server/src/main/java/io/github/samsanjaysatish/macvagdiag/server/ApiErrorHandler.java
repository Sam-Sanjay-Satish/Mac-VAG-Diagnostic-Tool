package io.github.samsanjaysatish.macvagdiag.server;

import io.github.samsanjaysatish.macvagdiag.scan.InvalidModuleAddressException;
import io.github.samsanjaysatish.macvagdiag.scan.ScanRefusedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps scan's refusals and bad input to HTTP. Every body is {@link ApiJson.Error}.
 *
 * <table>
 *   <caption>Error mapping</caption>
 *   <tr><td>{@code ADAPTER_NOT_CONNECTED}, {@code SERVICE_CLOSED}, interrupted</td><td>503</td></tr>
 *   <tr><td>{@code LIMIT_EXCEEDED}, {@code MODULE_NOT_RESPONDING} (scan's back-off: nothing sent)</td>
 *       <td>429 + {@code Retry-After}</td></tr>
 *   <tr><td>{@code SCAN_IN_PROGRESS}</td><td>409</td></tr>
 *   <tr><td>malformed or unknown module address</td><td>400 {@code INVALID_MODULE}</td></tr>
 * </table>
 *
 * A module that simply doesn't answer is not an error: scan returns it as data, and the API sends 200 with
 * {@code "status": "NOT_RESPONDING"}.
 */
@RestControllerAdvice
final class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ScanRefusedException.class)
    ResponseEntity<ApiJson.Error> refused(ScanRefusedException e) {
        HttpStatus status = switch (e.reason()) {
            case ADAPTER_NOT_CONNECTED, SERVICE_CLOSED -> HttpStatus.SERVICE_UNAVAILABLE;
            case LIMIT_EXCEEDED, MODULE_NOT_RESPONDING -> HttpStatus.TOO_MANY_REQUESTS;
            case SCAN_IN_PROGRESS -> HttpStatus.CONFLICT;
        };
        log.info("Refused by scan ({}): {}", e.reason(), e.getMessage());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        e.retryAfter().ifPresent(d -> response.header(HttpHeaders.RETRY_AFTER, Long.toString(ApiJson.seconds(d))));
        return response.body(ApiJson.error(e));
    }

    @ExceptionHandler(InvalidModuleAddressException.class)
    ResponseEntity<ApiJson.Error> invalidModule(InvalidModuleAddressException e) {
        // scan's message quotes the input escaped and lists the known modules.
        return body(HttpStatus.BAD_REQUEST, new ApiJson.Error("INVALID_MODULE", e.getMessage(), null, null, null));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiJson.Error> api(ApiException e) {
        return body(e.status(), new ApiJson.Error(e.code(), e.getMessage(), null, null, null));
    }

    @ExceptionHandler(InterruptedException.class)
    ResponseEntity<ApiJson.Error> interrupted(InterruptedException e) {
        Thread.currentThread().interrupt();
        return body(HttpStatus.SERVICE_UNAVAILABLE,
                new ApiJson.Error("INTERRUPTED", "interrupted: the app is shutting down", null, null, null));
    }

    private static ResponseEntity<ApiJson.Error> body(HttpStatus status, ApiJson.Error error) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(error);
    }
}
