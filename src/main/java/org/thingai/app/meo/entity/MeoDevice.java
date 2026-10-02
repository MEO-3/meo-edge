package org.thingai.app.meo.entity;

import org.thingai.base.dao.annotations.DaoColumn;
import org.thingai.base.dao.annotations.DaoTable;

@DaoTable(name = "meo_devices", version = 1)
public class MeoDevice {
    @DaoColumn(primaryKey = true, nullable = false)
    private String deviceId;
    @DaoColumn
    private String name;
    @DaoColumn
    private String description;
    @DaoColumn
    private String macAddress;
    @DaoColumn
    private int transportType;
    // Device model, reported by firmware during provisioning.
    @DaoColumn
    private String model;
    // Firmware version, reported by firmware during provisioning.
    @DaoColumn
    private String fwVersion;

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    public int getTransportType() {
        return transportType;
    }

    public void setTransportType(int transportType) {
        this.transportType = transportType;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getFwVersion() {
        return fwVersion;
    }

    public void setFwVersion(String fwVersion) {
        this.fwVersion = fwVersion;
    }
}
