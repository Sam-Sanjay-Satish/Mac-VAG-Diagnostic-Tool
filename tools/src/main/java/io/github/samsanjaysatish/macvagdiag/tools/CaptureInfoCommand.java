package io.github.samsanjaysatish.macvagdiag.tools;

import io.github.samsanjaysatish.macvagdiag.can.Capture;
import io.github.samsanjaysatish.macvagdiag.can.CaptureFormatException;
import io.github.samsanjaysatish.macvagdiag.can.CaptureReader;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

/** {@code capture-info <file>}: prints a {@link CaptureInfo} summary to stdout. An empty capture exits 1. */
final class CaptureInfoCommand {

    static final String USAGE = "Usage: " + Main.TOOL_NAME + " capture-info <capture file>\n"
            + "  Prints duration, frame count, first/last timestamps and frames per CAN ID.";

    private static final String PREFIX = "capture-info: ";

    private CaptureInfoCommand() {
    }

    static int run(List<String> args, Environment env) {
        if (Main.wantsHelp(args)) {
            env.out().println(USAGE);
            return Main.EXIT_OK;
        }
        if (args.size() != 1) {
            env.err().println(PREFIX + (args.isEmpty() ? "missing the capture file" : "expected one capture file"));
            env.err().println(USAGE);
            return Main.EXIT_USAGE;
        }
        Path file;
        try {
            file = Path.of(args.getFirst());
        } catch (InvalidPathException e) {
            env.err().println(PREFIX + "bad path '" + args.getFirst() + "': " + e.getReason());
            return Main.EXIT_USAGE;
        }
        Capture capture;
        try {
            capture = CaptureReader.read(file);
        } catch (NoSuchFileException e) {
            env.err().println(PREFIX + file + " not found");
            return Main.EXIT_FAILURE;
        } catch (CaptureFormatException e) {
            env.err().println(PREFIX + file + " is not a valid candump log: " + e.getMessage());
            return Main.EXIT_FAILURE;
        } catch (IOException e) {
            env.err().println(PREFIX + "could not read " + file + ": " + e.getMessage());
            return Main.EXIT_FAILURE;
        }
        CaptureInfo info = CaptureInfo.of(capture);
        info.print(env.out(), file.toString(), env.zone());
        if (info.frameCount() == 0) {
            env.err().println(PREFIX + "warning: the capture contains no frames");
            return Main.EXIT_FAILURE;
        }
        return Main.EXIT_OK;
    }
}
