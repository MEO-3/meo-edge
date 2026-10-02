package org.thingai.app.meo.define;

public final class MeoCmdErrCode {
    private MeoCmdErrCode() {}

    public static final int ERR_BAD_REQUEST = 1;
    public static final int ERR_UNKNOWN_CAP = 2;
    public static final int ERR_HANDLE_FAILED = 3;
    // Cap exists but has no handler for the requested op (read or write).
    public static final int ERR_OP_NOT_SUPPORTED = 4;

}
