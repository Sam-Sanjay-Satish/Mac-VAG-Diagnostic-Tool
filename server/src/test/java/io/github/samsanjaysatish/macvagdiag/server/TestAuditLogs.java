package io.github.samsanjaysatish.macvagdiag.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A fresh audit-log path per app test, so no test ever writes the user's real audit log. */
final class TestAuditLogs {

    private TestAuditLogs() {
    }

    static Path newFile(String name) {
        try {
            Path dir = Files.createTempDirectory("macvagdiag-server-test-");
            dir.toFile().deleteOnExit();
            return dir.resolve(name + "-audit.jsonl");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
