package org.thingai.app.meo.localapi.dto;

import org.thingai.app.meo.entity.MeoDevice;

// API read model: a device row with its cap keys and types in wire order. Not persisted.
public class DeviceResponse {
    private String deviceId;
    private String name;
    private String description;
    private String macAddress;
    private int transportType;
    private String model;
    private String fwVersion;
    private String[] caps;
    private int[] capTypes;

    public static DeviceResponse of(MeoDevice device, String[] caps, int[] capTypes) {
        DeviceResponse view = new DeviceResponse();
        view.deviceId = device.getDeviceId();
        view.name = device.getName();
        view.description = device.getDescription();
        view.macAddress = device.getMacAddress();
        view.transportType = device.getTransportType();
        view.model = device.getModel();
        view.fwVersion = device.getFwVersion();
        view.caps = caps != null ? caps : new String[0];
        view.capTypes = capTypes != null ? capTypes : new int[0];
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

    public int[] getCapTypes() {
        return capTypes;
    }
}
