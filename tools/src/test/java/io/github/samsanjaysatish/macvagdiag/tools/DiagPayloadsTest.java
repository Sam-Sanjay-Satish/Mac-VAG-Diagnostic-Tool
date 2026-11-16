package io.github.samsanjaysatish.macvagdiag.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.CanFrame;
import io.github.samsanjaysatish.macvagdiag.can.Hex;
import io.github.samsanjaysatish.macvagdiag.tools.DiagPayloads.Message;
import io.github.samsanjaysatish.macvagdiag.tools.DiagPayloads.Protocol;
import io.github.samsanjaysatish.macvagdiag.tools.DiagPayloads.Stream;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiagPayloadsTest {

    private static CanFrame f(int id, String hex) {
        return CanFrame.standardHex(id, hex);
    }

    private static String hex(Message m) {
        return Hex.format(m.bytes());
    }

    /** Setup to engine 01: we send on 740, receive on 300 (IDs as in the 2026-11-13 capture). */
    private static final List<CanFrame> SETUP_01 = List.of(
            f(0x200, "01 C0 00 10 00 03 01"),
            f(0x201, "00 D0 00 03 40 07 01"));

    @Test
    void tp20SingleAndMultiPacketMessagesWithPositions() {
        List<CanFrame> frames = new java.util.ArrayList<>(SETUP_01);
        frames.addAll(List.of(
                f(0x740, "A0 0F 8A FF 32 FF"),            // channel parameters: no payload
                f(0x300, "A1 0F 8A FF 4A FF"),
                f(0x740, "10 00 02 10 89"),               // last packet, 2 bytes: 10 89
                f(0x300, "B1"),                           // ACK
                f(0x300, "20 00 0A 5A 9B 30 33 4C"),      // 10 bytes over 2 packets
                f(0x300, "11 39 39 37 30 33 30 4D"),
                f(0x740, "B2")));
        List<Message> messages = DiagPayloads.reassemble(frames);

        assertEquals(2, messages.size());
        Message request = messages.get(0);
        assertEquals("10 89", hex(request));
        assertEquals(Protocol.TP20, request.protocol());
        assertEquals(0x740, request.canId());
        assertTrue(request.complete());
        assertArrayEquals(new int[] {4, 4}, request.frames());
        assertArrayEquals(new int[] {3, 4}, request.indexes());

        Message response = messages.get(1);
        assertEquals("5A 9B 30 33 4C 39 39 37 30 33", hex(response), "trailing byte after the length left out");
        assertTrue(response.complete());
        assertArrayEquals(new int[] {6, 6, 6, 6, 6, 7, 7, 7, 7, 7}, response.frames());
        assertArrayEquals(new int[] {3, 4, 5, 6, 7, 1, 2, 3, 4, 5}, response.indexes());
    }

    @Test
    void tp20LengthFlagOnResponsePendingIsMasked() {
        // 300#14 80 03 7F 22 78: bit 15 of the length is set on every 7F xx 78 the car sent.
        List<CanFrame> frames = new java.util.ArrayList<>(SETUP_01);
        frames.add(f(0x300, "14 80 03 7F 22 78"));
        frames.add(f(0x300, "15 00 03 62 F1 87"));
        List<Message> messages = DiagPayloads.reassemble(frames);
        assertEquals(List.of("7F 22 78", "62 F1 87"), messages.stream().map(DiagPayloadsTest::hex).toList());
        assertTrue(messages.getFirst().complete());
    }

    @Test
    void tp20DisconnectDropsAHalfReceivedMessageAndNewSetupResets() {
        List<CanFrame> frames = new java.util.ArrayList<>(SETUP_01);
        frames.add(f(0x300, "20 00 10 5A 9B 30 33 4C"));  // 16 bytes announced, 5 received
        frames.add(f(0x740, "A8"));
        frames.addAll(SETUP_01);
        frames.add(f(0x300, "11 00 01 50"));
        List<Message> messages = DiagPayloads.reassemble(frames);
        assertEquals(List.of("5A 9B 30 33 4C", "50"), messages.stream().map(DiagPayloadsTest::hex).toList());
        assertFalse(messages.get(0).complete());
        assertTrue(messages.get(1).complete());
    }

    @Test
    void setupReplyWithInvalidIdBitClaimsNothing() {
        // hi byte 10 = "invalid" bit: the module doesn't name that ID, so 300 stays ISO-TP.
        List<CanFrame> frames = List.of(
                f(0x200, "01 C0 00 10 00 03 01"),
                f(0x201, "00 D0 00 10 40 07 01"),
                f(0x300, "03 22 F1 90"));
        List<Message> messages = DiagPayloads.reassemble(frames);
        assertEquals(1, messages.size());
        assertEquals(Protocol.ISOTP, messages.getFirst().protocol());
        assertEquals("22 F1 90", hex(messages.getFirst()));
    }

    @Test
    void isotpSingleFirstConsecutiveFlowControlAndPadding() {
        List<CanFrame> frames = List.of(
                f(0x714, "03 22 F1 87 55 55 55 55"),       // VCDS pads with 55
                f(0x77E, "10 0E 62 F1 87 35 43 36"),       // 14 bytes
                f(0x714, "30 00 01 55 55 55 55 55"),       // flow control: no payload
                f(0x77E, "21 39 32 30 38 35 32 41"),
                f(0x77E, "22 20 AA AA AA AA AA AA"));      // 1 byte + ECU padding AA
        List<Message> messages = DiagPayloads.reassemble(frames);
        assertEquals(List.of("22 F1 87", "62 F1 87 35 43 36 39 32 30 38 35 32 41 20"),
                messages.stream().map(DiagPayloadsTest::hex).toList());
        Message response = messages.get(1);
        assertTrue(response.complete());
        assertEquals(4, response.frames()[response.frames().length - 1]);
        assertEquals(1, response.indexes()[response.indexes().length - 1]);
    }

    @Test
    void isotpConsecutiveFramesWithoutFirstFrameAndCutOffMessages() {
        List<CanFrame> frames = List.of(
                f(0x7E8, "22 50 30 34 32 37 38 38"),       // capture started mid-message
                f(0x7E8, "03 41 0D 00 AA AA AA AA"),
                f(0x77E, "10 20 62 F1 90 31 32 33"));      // 32 announced, capture ends
        List<Message> messages = DiagPayloads.reassemble(frames);
        assertEquals(3, messages.size());
        assertEquals("50 30 34 32 37 38 38", hex(messages.get(0)));
        assertFalse(messages.get(0).complete());
        assertEquals("41 0D 00", hex(messages.get(1)));
        assertEquals("62 F1 90 31 32 33", hex(messages.get(2)));
        assertFalse(messages.get(2).complete());
    }

    @Test
    void streamsConcatenateMessagesPerIdAndProtocol() {
        List<CanFrame> frames = List.of(
                f(0x7E8, "04 49 02 01 31 AA AA AA"),
                f(0x77E, "02 50 03 AA AA AA AA AA"),
                f(0x7E8, "03 4D 38 47 AA AA AA AA"));
        List<Stream> streams = DiagPayloads.streams(DiagPayloads.reassemble(frames));
        assertEquals(2, streams.size());
        assertEquals("7E8", streams.get(0).idText());
        assertEquals("49 02 01 31 4D 38 47", Hex.format(streams.get(0).bytes()));
        assertArrayEquals(new int[] {0, 0, 0, 0, 2, 2, 2}, streams.get(0).frames());
    }

    @Test
    void extendedIdsAreIsotpToo() {
        List<Message> messages = DiagPayloads.reassemble(List.of(CanFrame.extendedHex(0x18DAF110, "03 22 F1 90")));
        assertEquals("18DAF110", messages.getFirst().idText());
        assertEquals("22 F1 90", hex(messages.getFirst()));
    }

    @Test
    void decodesTp20IdFields() {
        assertEquals(0x300, DiagPayloads.decodeTp20Id(0x00, 0x03));
        assertEquals(0x32E, DiagPayloads.decodeTp20Id(0x2E, 0x03));
        assertEquals(0x740, DiagPayloads.decodeTp20Id(0x40, 0x07));
        assertEquals(-1, DiagPayloads.decodeTp20Id(0x00, 0x10));
    }
}
