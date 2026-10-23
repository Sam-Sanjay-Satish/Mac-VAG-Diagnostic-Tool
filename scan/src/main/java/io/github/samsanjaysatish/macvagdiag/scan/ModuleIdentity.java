package io.github.samsanjaysatish.macvagdiag.scan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One module's identification, as read for {@link ScanService#vehicleInfo}.
 *
 * @param module the module
 * @param reachability whether it answered
 * @param link the link it answered on
 * @param identification what it said, if it answered the identification requests
 * @param notes readable notes
 */
public record ModuleIdentity(ModuleAddress module, ModuleAvailability.Reachability reachability,
        Optional<ModuleLink> link, Optional<EcuInfo> identification, List<String> notes) {

    public ModuleIdentity {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(reachability, "reachability");
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(identification, "identification");
        notes = List.copyOf(notes);
    }
}
