package io.github.samsanjaysatish.macvagdiag.tools;

import io.github.samsanjaysatish.macvagdiag.can.CanAdapter;
import io.github.samsanjaysatish.macvagdiag.can.CanAdapterException;
import io.github.samsanjaysatish.macvagdiag.can.CaptureWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code can-log}: the listen-only logger. Parses the arguments, picks the port, then runs a
 * {@link CanLogger} with Ctrl-C wired to {@link CanLogger#shutdown()}. Messages go to stderr.
 */
final class CanLogCommand {

    private static final String PREFIX = "can-log: ";

    private CanLogCommand() {
    }

    static int run(List<String> args, Environment env) {
        PrintStream err = env.err();
        if (Main.wantsHelp(args)) {
            env.out().println(CanLogOptions.USAGE);
            return Main.EXIT_OK;
        }
        CanLogOptions options;
        PortChoice port;
        try {
            options = CanLogOptions.parse(args, env.time().now(), env.zone());
            port = PortChoice.resolve(options.port(), env.ports().get());
        } catch (UsageException e) {
            err.println(PREFIX + e.getMessage());
            err.println(CanLogOptions.USAGE);
            return Main.EXIT_USAGE;
        }
        Path output = options.output();
        if (Files.exists(output)) {
            err.println(PREFIX + output + " already exists; not overwriting it. Pass another --output.");
            return Main.EXIT_USAGE;
        }
        if (!isInsideLocal(output)) {
            err.println(PREFIX + "warning: " + output + " is outside local/. Raw captures contain the VIN; "
                    + "keep them out of git.");
        }
        if (port.path().startsWith("/dev/tty.")) {
            err.println(PREFIX + "warning: on macOS use the /dev/cu.* device, not /dev/tty.*");
        }

        CanAdapter adapter = env.adapters().apply(port.path());
        CanLogger logger = new CanLogger(adapter, () -> CaptureWriter.create(output),
                new CanLogger.Header(options.bitrate(), port.path(), port.firmware(), options.note()),
                env.time(), env.zone(), err, CanLogger.adapterErrorCounter(adapter),
                CanLogger.Timing.defaults(options.duration()), output.toString());
        env.shutdownHooks().accept(logger::shutdown);

        try {
            logger.start();
        } catch (CanAdapterException e) {
            reportOpenFailure(err, port.path(), e);
            return Main.EXIT_FAILURE;
        } catch (IOException e) {
            err.println(PREFIX + "could not create " + output + ": " + e.getMessage());
            err.println(PREFIX + "Nothing was transmitted; the adapter has been closed.");
            return Main.EXIT_FAILURE;
        }
        err.println(PREFIX + "listening on " + port.path() + " at " + options.bitrate().bitsPerSecond()
                + " bit/s in " + CanLogger.MODE + ", firmware " + port.firmware() + ". Writing " + output
                + ". Press Ctrl-C to stop.");
        CanLogger.End end = logger.loop();
        return end.isFailure() ? Main.EXIT_FAILURE : Main.EXIT_OK;
    }

    private static void reportOpenFailure(PrintStream err, String port, CanAdapterException e) {
        err.println(PREFIX + "could not open " + port + " in listen-only mode: " + e.getMessage());
        if (String.valueOf(e.getMessage()).contains("LISTEN_ONLY")) {
            err.println(PREFIX + "The CANable 2.0 stock slcan firmware has no working silent mode (it accepts M1, "
                    + "then O reopens the controller in normal mode, which ACKs frames on the car's bus), so the "
                    + "adapter refuses LISTEN_ONLY. See 'Listen-only / silent mode' in can-core/CLAUDE.md. "
                    + "can-log can't be used on the car until that is solved.");
        }
        err.println(PREFIX + "Nothing was transmitted and no capture file was created.");
    }

    /** True if some directory on the absolute path is named {@code local}. */
    static boolean isInsideLocal(Path output) {
        Path parent = output.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            return false;
        }
        for (Path part : parent) {
            if (part.toString().equals("local")) {
                return true;
            }
        }
        return false;
    }
}
