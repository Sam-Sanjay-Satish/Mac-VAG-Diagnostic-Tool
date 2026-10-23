package io.github.samsanjaysatish.macvagdiag.server;

import io.github.samsanjaysatish.macvagdiag.can.SlcanPortDiscovery;
import java.util.List;

/**
 * Lists candidate serial ports. The real one is {@link SlcanPortDiscovery#candidates()}; tests supply a fixed list
 * (a bean of this type overrides the default), so no test asks the OS for serial ports.
 */
@FunctionalInterface
public interface PortDiscovery {

    PortDiscovery SYSTEM = SlcanPortDiscovery::candidates;

    List<SlcanPortDiscovery.Candidate> candidates();
}
