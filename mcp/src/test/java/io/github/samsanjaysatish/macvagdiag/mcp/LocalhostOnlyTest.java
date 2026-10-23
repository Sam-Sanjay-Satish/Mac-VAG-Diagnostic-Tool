package io.github.samsanjaysatish.macvagdiag.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** DNS-rebinding defence: only loopback {@code Host} / {@code Origin}, with or without a port. */
class LocalhostOnlyTest {

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "localhost:8080", "LOCALHOST:8080", "127.0.0.1", "127.0.0.1:1",
            "[::1]", "[::1]:65535"})
    void loopbackHostsAreAccepted(String host) {
        assertTrue(LocalhostOnly.check(List.of(host), List.of()).isEmpty(), host);
    }

    @ParameterizedTest
    @ValueSource(strings = {"evil.example", "localhost.evil.example", "localhost:evil", "localhost:", "localhost:0",
            "localhost:65536", "localhost:8080:1", "127.0.0.2", "127.0.0.1.nip.io", "0.0.0.0", "::1", "[::1]x",
            "192.168.1.10:8080", "", " "})
    void otherHostsAreRefused(String host) {
        var r = LocalhostOnly.check(List.of(host), List.of());
        assertTrue(r.isPresent(), host);
        assertEquals(LocalhostOnly.BAD_HOST_STATUS, r.get().status());
    }

    @Test
    void missingOrRepeatedHostIsRefused() {
        assertTrue(LocalhostOnly.check(List.of(), List.of()).isPresent());
        assertTrue(LocalhostOnly.check(List.of("localhost", "evil.example"), List.of()).isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost", "http://localhost:8080", "https://127.0.0.1:8443", "http://[::1]:3000",
            "HTTP://LOCALHOST:8080"})
    void loopbackOriginsAreAccepted(String origin) {
        assertTrue(LocalhostOnly.check(List.of("localhost:8080"), List.of(origin)).isEmpty(), origin);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "", "http://evil.example", "http://localhost.evil.example",
            "http://localhost:8080.evil.example", "http://localhost:@evil.example", "http://localhost:8080/",
            "file://localhost", "ws://localhost:8080", "http://127.0.0.1:99999", "localhost:8080"})
    void otherOriginsAreRefused(String origin) {
        var r = LocalhostOnly.check(List.of("localhost:8080"), List.of(origin));
        assertTrue(r.isPresent(), origin);
        assertEquals(LocalhostOnly.BAD_ORIGIN_STATUS, r.get().status());
    }

    @Test
    void missingOriginIsFineButRepeatedOriginIsNot() {
        assertTrue(LocalhostOnly.check(List.of("localhost"), List.of()).isEmpty());
        assertTrue(LocalhostOnly.check(List.of("localhost"), List.of("http://localhost", "http://evil.example"))
                .isPresent());
    }
}
