package io.github.samsanjaysatish.macvagdiag.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The desktop app: a local web page and REST API on {@code 127.0.0.1}, over one shared
 * {@link io.github.samsanjaysatish.macvagdiag.scan.ScanService}. Read-only, like everything below it.
 *
 * <pre>
 * ./gradlew :server:bootRun --args=--demo                                  # simulated demo car
 * ./gradlew :server:bootRun                                                # CANable, auto-detected
 * ./gradlew :server:bootRun --args=--macvagdiag.port=/dev/cu.usbmodem14101 # CANable on a given port
 * </pre>
 *
 * Settings are listed in {@link MacVagDiagProperties}; the wiring is in {@link ScanServiceConfiguration}.
 */
@SpringBootApplication
public class MacVagDiagApplication {

    public static void main(String[] args) {
        SpringApplication.run(MacVagDiagApplication.class, args);
    }
}
