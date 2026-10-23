package io.github.samsanjaysatish.macvagdiag.tools;

import io.github.samsanjaysatish.macvagdiag.can.CanAdapter;
import io.github.samsanjaysatish.macvagdiag.can.SlcanAdapter;
import io.github.samsanjaysatish.macvagdiag.can.SlcanPortDiscovery;
import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import java.io.PrintStream;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Everything the commands take from the outside world, so tests can swap in fakes.
 *
 * @param out command output (reports, port lists)
 * @param err diagnostics, the logger's live summary and final stats
 * @param time clock for timestamps, flush and summary intervals
 * @param zone time zone for the capture header date and the default file name
 * @param ports serial port discovery
 * @param adapters makes the adapter for a serial port path; in {@link #system()} this is the only place
 *     a real {@link SlcanAdapter} is created
 * @param shutdownHooks registers work to run on Ctrl-C / JVM exit
 */
record Environment(PrintStream out, PrintStream err, TimeSource time, ZoneId zone,
                   Supplier<List<SlcanPortDiscovery.Candidate>> ports,
                   Function<String, CanAdapter> adapters,
                   Consumer<Runnable> shutdownHooks) {

    Environment {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(ports, "ports");
        Objects.requireNonNull(adapters, "adapters");
        Objects.requireNonNull(shutdownHooks, "shutdownHooks");
    }

    /** The real machine: System streams, real clock, real serial ports. */
    static Environment system() {
        TimeSource time = TimeSource.system();
        return new Environment(System.out, System.err, time, ZoneId.systemDefault(),
                SlcanPortDiscovery::candidates,
                path -> SlcanAdapter.forPort(path, time),
                hook -> Runtime.getRuntime().addShutdownHook(new Thread(hook, "can-log-shutdown")));
    }
}
