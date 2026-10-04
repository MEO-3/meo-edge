package org.thingai.app.meo.handler.msg;

import org.junit.jupiter.api.Test;
import org.thingai.app.meo.define.MeoEdgeMsgOpcode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Byte layout must match the firmware; the packed type|seq byte and negative i16 are the easy things to get wrong.
class EdgeMsgDtoTest {

    @Test
    void writeEncodesTypeSeqAndNegativeValue() {
        byte[] bytes = new EdgeMsgDto(MeoEdgeMsgOpcode.WRITE, 31, 2, -52).toBytes();
        // 001 11111 = 0x3F
        assertArrayEquals(new byte[]{0x3F, 0x02, (byte) 0xCC, (byte) 0xFF}, bytes);
    }

    @Test
    void parsesErrReply() {
        // 011 00101 = 0x65: ERR, seq 5, error code 4
        EdgeMsgDto frame = EdgeMsgDto.parse(new byte[]{0x65, 0x00, 0x04, 0x00});
        assertEquals(MeoEdgeMsgOpcode.ERR, frame.getType());
        assertEquals(5, frame.getSeq());
        assertEquals(4, frame.getValue());
    }

    @Test
    void parsesEventWithHighIdx() {
        // 100 00000 = 0x80: EVENT, idx 255, value 235
        EdgeMsgDto frame = EdgeMsgDto.parse(new byte[]{(byte) 0x80, (byte) 0xFF, (byte) 0xEB, 0x00});
        assertEquals(MeoEdgeMsgOpcode.EVENT, frame.getType());
        assertEquals(255, frame.getIdx());
        assertEquals(235, frame.getValue());
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> new EdgeMsgDto(MeoEdgeMsgOpcode.WRITE, 0, 0, 32768));
        assertThrows(IllegalArgumentException.class, () -> new EdgeMsgDto(MeoEdgeMsgOpcode.READ, 32, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> EdgeMsgDto.parse(new byte[]{(byte) 0xA0, 0, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> EdgeMsgDto.parse(new byte[3]));
    }
}
