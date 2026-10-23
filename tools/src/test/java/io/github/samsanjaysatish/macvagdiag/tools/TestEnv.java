package io.github.samsanjaysatish.macvagdiag.tools;

import io.github.samsanjaysatish.macvagdiag.can.CanAdapter;
import io.github.samsanjaysatish.macvagdiag.can.SlcanPortDiscovery.Candidate;
import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** A fake {@link Environment}: captured stdout/stderr, a fake clock, scripted ports and adapters, recorded hooks. */
final class TestEnv {

    static final String CANABLE_PORT = "/dev/cu.usbmodem14101";
    static final String FIRMWARE = "CANable2 b158aa7 github.com/normaldotcom/canable2-fw.git";
    static final Candidate CANABLE = new Candidate(CANABLE_PORT, FIRMWARE, 0x16D0, 0x117E);
    static final Candidate ARDUINO = new Candidate("/dev/cu.usbmodem3101", "Arduino Uno", 0x2341, 0x0043);

    final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    final List<Runnable> hooks = new ArrayList<>();
    final List<String> requestedPorts = new ArrayList<>();
    final Environment env;

    TestEnv(TimeSource time, List<Candidate> ports, Function<String, CanAdapter> adapters) {
        env = new Environment(
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8),
                time, ZoneOffset.UTC, () -> ports,
                path -> {
                    requestedPorts.add(path);
                    return adapters.apply(path);
                },
                hooks::add);
    }

    /** No adapter may be created. */
    TestEnv(TimeSource time, List<Candidate> ports) {
        this(time, ports, path -> {
            throw new AssertionError("no adapter expected, asked for " + path);
        });
    }

    int run(String... args) {
        return Main.run(args, env);
    }

    String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }
}
