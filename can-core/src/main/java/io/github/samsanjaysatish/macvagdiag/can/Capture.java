package io.github.samsanjaysatish.macvagdiag.can;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A capture file's contents.
 *
 * @param metadata {@code # key: value} comment lines, in file order
 * @param comments other {@code #} comment lines, without the {@code #}
 * @param frames frame lines, in file order
 */
public record Capture(Map<String, String> metadata, List<String> comments, List<CapturedFrame> frames) {

    public Capture {
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        comments = List.copyOf(comments);
        frames = List.copyOf(frames);
    }

    /** Just the frames, without timestamps. */
    public List<CanFrame> canFrames() {
        return frames.stream().map(CapturedFrame::frame).toList();
    }
}
