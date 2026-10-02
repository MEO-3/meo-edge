package org.thingai.app.meo.entity;

import org.thingai.base.dao.annotations.DaoColumn;
import org.thingai.base.dao.annotations.DaoTable;

// One row per device-defined capability. idx is the cap's position in the device's
// report and its id on the binary wire. Re-provisioning replaces all rows.
@DaoTable(name = "meo_device_caps", version = 1)
public class MeoDeviceCap {
    @DaoColumn(primaryKey = true, autoIncrement = true)
    private int id;
    @DaoColumn(nullable = false)
    private String deviceId;
    @DaoColumn(nullable = false)
    private String cap;
    @DaoColumn(nullable = false)
    private int idx;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getCap() {
        return cap;
    }

    public void setCap(String cap) {
        this.cap = cap;
    }

    public int getIdx() {
        return idx;
    }

    public void setIdx(int idx) {
        this.idx = idx;
    }
}
