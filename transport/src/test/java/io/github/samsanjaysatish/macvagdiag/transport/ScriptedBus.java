package io.github.samsanjaysatish.macvagdiag.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.samsanjaysatish.macvagdiag.can.CanFrame;
import io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter;
import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A strict fake module for hand-written-frame tests: the exact frames the tester must send, in order, each with
 * the module's replies. A frame that doesn't match the next expected one is recorded as a mismatch and gets no
 * reply. Also records when each frame was sent, for pacing assertions.
 */
final class ScriptedBus implements SimulatedAdapter.Responder {

    private record Step(CanFrame expected, List<SimulatedAdapter.Reply> replies) {
    }

    private final TimeSource time;
    private final Deque<Step> steps = new ArrayDeque<>();
    private final List<String> mismatches = new ArrayList<>();
    private final List<Long> sendTimes = new ArrayList<>();

    ScriptedBus(TimeSource time) {
        this.time = time;
    }

    /** The tester must send {@code id [..] hex} next; the module answers with {@code replies}. */
    ScriptedBus expect(int id, String hex, SimulatedAdapter.Reply... replies) {
        steps.add(new Step(CanFrame.standardHex(id, hex), List.of(replies)));
        return this;
    }

    static SimulatedAdapter.Reply reply(int id, String hex) {
        return SimulatedAdapter.Reply.now(CanFrame.standardHex(id, hex));
    }

    static SimulatedAdapter.Reply replyAfter(Duration delay, int id, String hex) {
        return SimulatedAdapter.Reply.after(delay, CanFrame.standardHex(id, hex));
    }

    @Override
    public synchronized List<SimulatedAdapter.Reply> respond(CanFrame sent) {
        sendTimes.add(time.nanoTime());
        Step step = steps.poll();
        if (step == null) {
            mismatches.add("unexpected extra frame " + sent);
            return List.of();
        }
        if (!step.expected().equals(sent)) {
            mismatches.add("expected " + step.expected() + " but tester sent " + sent);
            return List.of();
        }
        return step.replies();
    }

    /** Nanos at which each frame was sent, in order. */
    synchronized List<Long> sendTimes() {
        return List.copyOf(sendTimes);
    }

    /** Every expected frame was sent, in order, and nothing else. */
    synchronized void assertComplete() {
        assertEquals(List.of(), mismatches, "frames that didn't match the script");
        assertEquals(List.of(), steps.stream().map(s -> s.expected().toString()).toList(), "expected frames never sent");
    }
}
