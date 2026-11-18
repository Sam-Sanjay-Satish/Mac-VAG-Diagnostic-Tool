import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Stands for the one CANable: only one process can own its serial port. Every hardwareTest task uses this
 * service, registered with maxParallelUsages = 1, so with org.gradle.parallel=true they run one after another
 * instead of fighting over the port (2026-11-17: can-core and tools ran at once and one failed to open it).
 */
abstract class CanAdapterLock : BuildService<BuildServiceParameters.None>
