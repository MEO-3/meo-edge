package org.thingai.app.meo.entity;

import org.thingai.base.dao.annotations.DaoColumn;
import org.thingai.base.dao.annotations.DaoTable;

@DaoTable(name = "meo_device_caps", version = 1)
public class MeoDeviceCap {
    @DaoColumn(primaryKey = true, nullable = false)
    private String deviceId;
    @DaoColumn(nullable = false)
    private String caps; // json array of cap keys, in report order

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getCaps() {
        return caps;
    }

    public void setCaps(String caps) {
        this.caps = caps;
    }
}
