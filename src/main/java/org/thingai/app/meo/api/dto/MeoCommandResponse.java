package org.thingai.app.meo.api.dto;

// Result of a device command: the value the device reported back. Echoes
// deviceId and cap so a caller firing several commands can tell them apart.
public class MeoCommandResponse {
    private String deviceId;
    private String cap;
    private int value;

    public static MeoCommandResponse of(String deviceId, String cap, int value) {
        MeoCommandResponse view = new MeoCommandResponse();
        view.deviceId = deviceId;
        view.cap = cap;
        view.value = value;
        return view;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getCap() {
        return cap;
    }

    public int getValue() {
        return value;
    }
}
