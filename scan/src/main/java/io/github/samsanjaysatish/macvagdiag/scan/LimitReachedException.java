package io.github.samsanjaysatish.macvagdiag.scan;

/** Inside scan: a budget or rate limit stopped the next operation before anything was sent. */
final class LimitReachedException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient LimitHit hit;

    LimitReachedException(LimitHit hit) {
        super(hit.message());
        this.hit = hit;
    }

    LimitHit hit() {
        return hit;
    }
}
