package io.github.samsanjaysatish.macvagdiag.server;

import java.util.Objects;
import org.springframework.http.HttpStatus;

/** An API error raised by the controller itself (unknown scan, report not ready, bad body). Fixed messages only. */
final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;

    ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = Objects.requireNonNull(status, "status");
        this.code = Objects.requireNonNull(code, "code");
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
