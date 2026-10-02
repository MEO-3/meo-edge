package org.thingai.app.meo.define;

// Distinct from HTTP status codes.
public final class MeoErr {
    public static int ERROR = 0;

    public static int PROV_FAILED = 100;
    public static int PROV_SCAN_FAILED = 101;
    public static int PROV_CONNECT_FAILED = 102;
    public static int PROV_SETUP_FAILED = 103;
    public static int PROV_PRESIST_FAILED = 104;

    public static int DEVICE_NOT_FOUND = 200;
    public static int DEVICE_UPDATE_FAILED = 201;
} 
