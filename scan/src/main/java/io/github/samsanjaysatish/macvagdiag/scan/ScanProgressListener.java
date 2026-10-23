package io.github.samsanjaysatish.macvagdiag.scan;

/**
 * Receives Auto-Scan progress. Called on the scan's own thread, in order; keep it quick and don't call back into
 * {@link ScanService} from it. An exception thrown here is logged and ignored.
 */
@FunctionalInterface
public interface ScanProgressListener {

    ScanProgressListener NONE = event -> {
    };

    void onEvent(ScanEvent event);
}
