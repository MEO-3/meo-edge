package org.thingai.app.meo.entity;

import org.thingai.app.meo.define.MeoDevProvisionStatus;

public class MeoDeviceProvision {
    private String bleAddress;
    private String macAddress;
    private String model;
    private String fwVersion;
    private String[] caps;
    private int[] capTypes;
    private String wifiSsid;
    private String provisionStatus;
    private int status = MeoDevProvisionStatus.STATUS_CREATED;
    private String message;

    public String getBleAddress() {
        return bleAddress;
    }

    public void setBleAddress(String bleAddress) {
        this.bleAddress = bleAddress;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
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

    public String[] getCaps() {
        return caps;
    }

    public void setCaps(String[] caps) {
        this.caps = caps;
    }

    public int[] getCapTypes() {
        return capTypes;
    }

    public void setCapTypes(int[] capTypes) {
        this.capTypes = capTypes;
    }

    public String getWifiSsid() {
        return wifiSsid;
    }

    public void setWifiSsid(String wifiSsid) {
        this.wifiSsid = wifiSsid;
    }

    public String getProvisionStatus() {
        return provisionStatus;
    }

    public void setProvisionStatus(String provisionStatus) {
        this.provisionStatus = provisionStatus;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
