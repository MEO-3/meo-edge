package org.thingai.app.meo.define;

public final class MeoDevTransportType {
    private MeoDevTransportType() {

    }

    public static final int GENERIC = 0;
    public static final int WIFI_LAN = 1;
    public static final int BLE = 2;
    public static final int ZIGBEE = 3;
    public static final int LORA = 4;
    public static final int MATTER = 5;

    // industrial serial protocols
    public static final int RS485 = 20;
    public static final int RS232 = 21;
}
