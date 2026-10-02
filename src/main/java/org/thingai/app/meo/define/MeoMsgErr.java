package org.thingai.app.meo.define;

public final class MeoMsgErr {
    private MeoMsgErr() {}

    public static final int GENERIC = 0;
    public static final int BAD_REQUEST = 1;
    public static final int UNKNOWN_CAP = 2;
    public static final int HANDLE_FAILED = 3;
    public static final int OP_NOT_SUPPORTED = 4;

    // Edge-side only, never sent by a device.
    public static final int TIMEOUT = 5;
    public static final int SEND_FAILED = 6;

}
