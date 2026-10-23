package io.github.samsanjaysatish.macvagdiag.tools;

import java.util.Arrays;
import java.util.List;

/**
 * Entry point for the command-line tools. Subcommands:
 * <ul>
 *   <li>{@code can-log}: the listen-only CAN logger ({@link CanLogCommand})</li>
 *   <li>{@code list-ports}: serial ports that look like a CANable ({@link ListPortsCommand})</li>
 *   <li>{@code capture-info}: summary of a capture file ({@link CaptureInfoCommand})</li>
 * </ul>
 * Exit codes: 0 success, 1 failure, 2 bad arguments.
 */
public final class Main {

    static final String TOOL_NAME = "macvagdiag-tools";
    static final String TOOL_VERSION = "0.1.0-dev";

    static final int EXIT_OK = 0;
    static final int EXIT_FAILURE = 1;
    static final int EXIT_USAGE = 2;

    static final String USAGE = """
            Usage: %s <command> [options]
            Commands:
              can-log       record CAN traffic in listen-only mode to a candump log file
              list-ports    list serial ports that look like a CANable
              capture-info  summarize a capture file
            Run '%s <command> --help' for a command's options.""".formatted(TOOL_NAME, TOOL_NAME);

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, Environment.system()));
    }

    /** Runs one command; returns the exit code. */
    static int run(String[] args, Environment env) {
        if (args.length == 0) {
            env.err().println(USAGE);
            return EXIT_USAGE;
        }
        List<String> rest = Arrays.asList(args).subList(1, args.length);
        return switch (args[0]) {
            case "can-log" -> CanLogCommand.run(rest, env);
            case "list-ports" -> ListPortsCommand.run(rest, env);
            case "capture-info" -> CaptureInfoCommand.run(rest, env);
            case "--help", "-h", "help" -> {
                env.out().println(USAGE);
                yield EXIT_OK;
            }
            case "--version" -> {
                env.out().println(toolNameAndVersion());
                yield EXIT_OK;
            }
            default -> {
                env.err().println(TOOL_NAME + ": unknown command '" + args[0] + "'");
                env.err().println(USAGE);
                yield EXIT_USAGE;
            }
        };
    }

    static String toolNameAndVersion() {
        return TOOL_NAME + " " + TOOL_VERSION;
    }

    /** True if the arguments ask for a command's help text. */
    static boolean wantsHelp(List<String> args) {
        return args.contains("--help") || args.contains("-h");
    }
}
