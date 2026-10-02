package org.thingai.app.meo.define;

// Distinct from HTTP status codes.
public final class ErrorCode {
    public static int ERROR = 0;

    public static int PROV_FAILED = 100;
    public static int PROV_SCAN_FAILED = 101;
    public static int PROV_CONNECT_FAILED = 102;
    public static int PROV_SETUP_FAILED = 103;
    public static int PROV_PRESIST_FAILED = 104;

    public static int DEVICE_NOT_FOUND = 200;
    public static int DEVICE_UPDATE_FAILED = 201;

    public static int CONTROL_FAILED = 300;
    public static int CONTROL_CAP_NOT_SUPPORTED = 301;
    public static int CONTROL_TIMEOUT = 302;
    // Command reached the device but it rejected or failed to run it.
    public static int CONTROL_DEVICE_ERROR = 303;
    public static int CONTROL_OP_NOT_SUPPORTED = 304;
}
