package io.github.samsanjaysatish.macvagdiag.tools;

import io.github.samsanjaysatish.macvagdiag.can.SlcanPortDiscovery;
import io.github.samsanjaysatish.macvagdiag.can.SlcanPortDiscovery.Candidate;
import java.io.PrintStream;
import java.util.List;
import java.util.Locale;

/**
 * {@code list-ports}: prints the {@code /dev/cu.usbmodem*} ports, CANables first, so the user can pick
 * the right one. Only enumerates ports; it opens nothing and sends nothing.
 */
final class ListPortsCommand {

    static final String USAGE = "Usage: " + Main.TOOL_NAME + " list-ports\n"
            + "  Lists " + SlcanPortDiscovery.CANDIDATE_PREFIX + "* serial ports, CANables (USB 16D0:117E) first.";

    private ListPortsCommand() {
    }

    static int run(List<String> args, Environment env) {
        if (Main.wantsHelp(args)) {
            env.out().println(USAGE);
            return Main.EXIT_OK;
        }
        if (!args.isEmpty()) {
            env.err().println("list-ports: unexpected argument '" + args.getFirst() + "'");
            env.err().println(USAGE);
            return Main.EXIT_USAGE;
        }
        List<Candidate> ports = env.ports().get();
        if (ports.isEmpty()) {
            env.err().println("list-ports: no " + SlcanPortDiscovery.CANDIDATE_PREFIX
                    + "* ports found. Is the CANable plugged in?");
            return Main.EXIT_FAILURE;
        }
        PrintStream out = env.out();
        int width = ports.stream().mapToInt(c -> c.path().length()).max().orElse(0);
        for (Candidate c : ports) {
            String description = c.description() == null || c.description().isBlank() ? "-" : c.description().strip();
            out.println(String.format(Locale.ROOT, "%-" + width + "s  %-7s  %s  %s", c.path(),
                    c.isCanable() ? "CANable" : "-", usbId(c), description));
        }
        long canables = ports.stream().filter(Candidate::isCanable).count();
        if (canables == 1) {
            out.println("One CANable found; can-log will use it without --port.");
        } else if (canables > 1) {
            out.println(canables + " CANables found; pass --port to can-log.");
        } else {
            out.println("No CANable (USB 16D0:117E) found among these ports.");
        }
        return Main.EXIT_OK;
    }

    private static String usbId(Candidate c) {
        if (c.vendorId() < 0 || c.productId() < 0) {
            return "????:????";
        }
        return String.format(Locale.ROOT, "%04X:%04X", c.vendorId(), c.productId());
    }
}
