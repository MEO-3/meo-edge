package org.thingai.app.meo.define;

// err code off meo system
public final class MeoErr {
    private MeoErr() {}

    // generic err, best practice not to use this or all kind of generic err code
    public static final int ERR = 0;

    // 1–99: occurred on the device
    public static final int BAD_REQUEST = 1;
    public static final int UNKNOWN_CAP = 2;
    public static final int HANDLE_FAILED = 3;
    public static final int OP_NOT_SUPPORTED = 4;

    // 100–199: occurred on the edge.
    public static final int EDGE_ERR = 100;
    public static final int PROV_SCAN_FAILED = 101;
    public static final int PROV_CONNECT_FAILED = 102;
    public static final int PROV_SETUP_FAILED = 103;
    public static final int PROV_PRESIST_FAILED = 104;

    public static final int DEVICE_NOT_FOUND = 110;
    public static final int DEVICE_UPDATE_FAILED = 111;

    public static final int MSG_TIMEOUT = 120;
    public static final int MSG_SEND_FAILED = 121;

    // 200–299: occurred in the cloud.
    public static final int CLOUD_ERR = 200;
}
