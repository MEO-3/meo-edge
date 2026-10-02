package org.thingai.app.meo.api.dto;

import org.thingai.app.meo.entity.MeoDevice;

// API read model: a device row with its cap keys in wire order. Not persisted.
public class MeoDeviceResponse {
    private String deviceId;
    private String name;
    private String description;
    private String macAddress;
    private int transportType;
    private String model;
    private String fwVersion;
    private String[] caps;

    public static MeoDeviceResponse of(MeoDevice device, String[] caps) {
        MeoDeviceResponse view = new MeoDeviceResponse();
        view.deviceId = device.getDeviceId();
        view.name = device.getName();
        view.description = device.getDescription();
        view.macAddress = device.getMacAddress();
        view.transportType = device.getTransportType();
        view.model = device.getModel();
        view.fwVersion = device.getFwVersion();
        view.caps = caps != null ? caps : new String[0];
        return view;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public int getTransportType() {
        return transportType;
    }

    public String getModel() {
        return model;
    }

    public String getFwVersion() {
        return fwVersion;
    }

    public String[] getCaps() {
        return caps;
    }
}
