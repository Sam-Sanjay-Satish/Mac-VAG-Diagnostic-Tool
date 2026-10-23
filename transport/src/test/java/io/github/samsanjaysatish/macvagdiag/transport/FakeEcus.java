package io.github.samsanjaysatish.macvagdiag.transport;

import io.github.samsanjaysatish.macvagdiag.can.CanFrame;
import io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter;
import io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter.Reply;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lenient fake modules for tests that care about the guard, not the frame bytes: a TP 2.0 module and an
 * ISO-TP module that answer any request with a positive response ({@code SID + 40}, then the request's
 * parameters). UDS {@code 3E 80} (suppress positive response) gets no answer, like a real ECU.
 */
final class FakeEcus {

    static final int TP20_ADDRESS = 0x01;
    static final int TP20_TESTER_TX = 0x740;
    static final int TP20_TESTER_RX = 0x300;

    private FakeEcus() {
    }

    static byte[] positiveResponse(byte[] request) {
        byte[] response = request.clone();
        response[0] = (byte) (request[0] + 0x40);
        return response;
    }

    /** A TP 2.0 module at address 01: channel on 740 (tester tx) / 300 (tester rx), block size 15. */
    static SimulatedAdapter.Responder tp20() {
        return new SimulatedAdapter.Responder() {
            private int moduleSeq;
            private ByteArrayOutputStream request;
            private int requestLength;

            @Override
            public synchronized List<Reply> respond(CanFrame sent) {
                if (sent.id() == 0x200 && sent.dataByte(0) == TP20_ADDRESS && sent.dataByte(1) == 0xC0) {
                    moduleSeq = 0;
                    return List.of(Reply.now(CanFrame.standardHex(0x200 + TP20_ADDRESS, "00 D0 00 03 40 07 01")));
                }
                if (sent.id() != TP20_TESTER_TX || sent.length() == 0) {
                    return List.of();
                }
                int b0 = sent.dataByte(0);
                if (b0 == 0xA0 || b0 == 0xA3) {
                    return List.of(Reply.now(CanFrame.standardHex(TP20_TESTER_RX, "A1 0F 8A FF 4A FF")));
                }
                if (b0 == 0xA8) {
                    return List.of(Reply.now(CanFrame.standardHex(TP20_TESTER_RX, "A8")));
                }
                int op = b0 >> 4;
                if (op > 3) {
                    return List.of(); // the tester's ACKs
                }
                byte[] data = sent.data();
                int from = 1;
                if (request == null) {
                    requestLength = (Byte.toUnsignedInt(data[1]) << 8) | Byte.toUnsignedInt(data[2]);
                    request = new ByteArrayOutputStream();
                    from = 3;
                }
                request.write(data, from, data.length - from);
                List<Reply> replies = new ArrayList<>();
                if (op == 0 || op == 1) {
                    replies.add(Reply.now(CanFrame.standard(TP20_TESTER_RX, 0xB0 | ((b0 + 1) & 0x0F))));
                }
                if ((op == 1 || op == 3) && request.size() == requestLength) {
                    byte[] response = positiveResponse(request.toByteArray());
                    request = null;
                    for (byte[] packet : packets(response)) {
                        replies.add(Reply.now(new CanFrame(TP20_TESTER_RX, false, packet)));
                    }
                }
                return replies;
            }

            private List<byte[]> packets(byte[] message) {
                List<byte[]> out = new ArrayList<>();
                List<byte[]> chunks = Tp20Channel.segment(message);
                for (int i = 0; i < chunks.size(); i++) {
                    int op = i == chunks.size() - 1 ? 0x1 : 0x2;
                    byte[] chunk = chunks.get(i);
                    byte[] frame = new byte[1 + chunk.length];
                    frame[0] = (byte) ((op << 4) | (moduleSeq & 0x0F));
                    System.arraycopy(chunk, 0, frame, 1, chunk.length);
                    moduleSeq = (moduleSeq + 1) & 0x0F;
                    out.add(frame);
                }
                return out;
            }
        };
    }

    /** An ISO-TP module on 7E0 → 7E8 answering single-frame requests with single-frame responses. */
    static SimulatedAdapter.Responder isoTp() {
        return sent -> {
            if (sent.id() != 0x7E0 || sent.length() == 0 || (sent.dataByte(0) >> 4) != 0) {
                return List.of();
            }
            int length = sent.dataByte(0) & 0x0F;
            byte[] request = Arrays.copyOfRange(sent.data(), 1, 1 + length);
            if (request.length == 2 && request[0] == 0x3E && request[1] == (byte) 0x80) {
                return List.of(); // suppressed positive response
            }
            byte[] response = positiveResponse(request);
            byte[] frame = new byte[8];
            Arrays.fill(frame, (byte) 0xAA);
            frame[0] = (byte) response.length;
            System.arraycopy(response, 0, frame, 1, response.length);
            return List.of(Reply.now(new CanFrame(0x7E8, false, frame)));
        };
    }
}
