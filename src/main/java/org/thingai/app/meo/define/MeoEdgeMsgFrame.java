package org.thingai.app.meo.define;

import org.thingai.app.meo.util.ByteUtil;

/**
 * The one device frame, on both down and up topics: u8 type(3b)|seq(5b) | u8 idx | i16 value LE.
 * seq correlates OK/ERR with the READ/WRITE that caused it; ERR carries the error code in value.
 */
public final class MeoEdgeMsgFrame {
    public static final int SIZE = 4;

    public static final int TYPE_READ = 0;
    public static final int TYPE_WRITE = 1;
    public static final int TYPE_OK = 2;
    public static final int TYPE_ERR = 3;
    public static final int TYPE_EVENT = 4;

    public static final int MAX_SEQ = 0x1F;
    public static final int MAX_IDX = 0xFF;

    private final int type;
    private final int seq;
    private final int idx;
    private final int value;

    public MeoEdgeMsgFrame(int type, int seq, int idx, int value) {
        if (type < TYPE_READ || type > TYPE_EVENT) {
            throw new IllegalArgumentException("unknown frame type: " + type);
        }
        if (seq < 0 || seq > MAX_SEQ) {
            throw new IllegalArgumentException("seq out of 5-bit range: " + seq);
        }
        if (idx < 0 || idx > MAX_IDX) {
            throw new IllegalArgumentException("idx out of uint8 range: " + idx);
        }
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            throw new IllegalArgumentException("value out of int16 range: " + value);
        }
        this.type = type;
        this.seq = seq;
        this.idx = idx;
        this.value = value;
    }

    public static MeoEdgeMsgFrame parse(byte[] payload) {
        if (payload == null || payload.length != SIZE) {
            throw new IllegalArgumentException(
                    "frame must be " + SIZE + " bytes, got "
                            + (payload == null ? "null" : String.valueOf(payload.length)));
        }
        int head = payload[0] & 0xFF;
        return new MeoEdgeMsgFrame(head >> 5, head & MAX_SEQ, payload[1] & 0xFF, ByteUtil.getI16LE(payload, 2));
    }

    public byte[] toBytes() {
        byte[] buf = new byte[SIZE];
        buf[0] = (byte) (type << 5 | seq);
        buf[1] = (byte) idx;

        ByteUtil.putU16LE(buf, 2, value);
        return buf;
    }

    public int getType() {
        return type;
    }

    public int getSeq() {
        return seq;
    }

    public int getIdx() {
        return idx;
    }

    /** Value read, written or reported; the MeoErr device code (1–99) for TYPE_ERR. */
    public int getValue() {
        return value;
    }
}
