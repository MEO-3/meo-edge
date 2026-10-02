package org.thingai.app.meo.define;

public final class MeoDevProvisionStatus {
    private MeoDevProvisionStatus() {
    }

    public static final int STATUS_GENERIC = 0;
    public static final int STATUS_CREATED = 1;
    public static final int STATUS_SCANNING = 2;
    public static final int STATUS_CONNECTING_BLE = 3;
    public static final int STATUS_CONNECTED_BLE = 4;
    public static final int STATUS_READING_MAC = 5;
    public static final int STATUS_READING_CAPABILITIES = 6;
    public static final int STATUS_WRITING_WIFI = 7;
    public static final int STATUS_READING_STATUS = 8;
    public static final int STATUS_DISCONNECTING_BLE = 9;
    public static final int STATUS_DISCONNECTED_BLE = 10;
    public static final int STATUS_FAILED = 11;
    public static final int STATUS_PROVISIONED = 12;
}
