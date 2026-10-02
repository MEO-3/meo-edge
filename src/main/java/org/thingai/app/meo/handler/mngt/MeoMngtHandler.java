package org.thingai.app.meo.handler.mngt;

import org.thingai.app.meo.entity.MeoDevice;
import org.thingai.app.meo.entity.MeoDeviceCap;
import org.thingai.base.log.ILog;
import org.thingai.base.dao.Dao;

import java.util.Arrays;
import java.util.Comparator;

public class MeoMngtHandler {
    private static final String TAG = "MeoMngtHandler";

    private final Dao dao;

    public MeoMngtHandler(Dao dao) {
        this.dao = dao;
    }

    public MeoDevice getDevice(String deviceId) {
        if (deviceId == null || deviceId.trim().isEmpty()) {
            return null;
        }
        MeoDevice[] devices = dao.query(MeoDevice.class, "deviceId", deviceId);
        return devices != null && devices.length > 0 ? devices[0] : null;
    }

    public MeoDevice[] getDevices() {
        MeoDevice[] devices = dao.readAll(MeoDevice.class);
        return devices != null ? devices : new MeoDevice[0];
    }

    // Only user metadata is updatable; identity (deviceId, macAddress) and
    // firmware-reported fields (model, fwVersion, transportType, caps) are owned by
    // the provisioning flow.
    public MeoDevice updateDevice(String deviceId, MeoDevice update) {
        MeoDevice existing = getDevice(deviceId);
        if (existing == null || update == null) {
            return null;
        }
        existing.setName(update.getName());
        existing.setDescription(update.getDescription());
        dao.insertOrUpdate(existing);
        ILog.i(TAG, "updateDevice", "updated deviceId=" + deviceId);
        return existing;
    }

    public MeoDevice deleteDevice(String deviceId) {
        MeoDevice existing = getDevice(deviceId);
        if (existing == null) {
            return null;
        }
        dao.delete(existing);
        dao.deleteByColumn(MeoDeviceCap.class, "deviceId", deviceId);
        ILog.i(TAG, "deleteDevice", "deleted deviceId=" + deviceId);
        return existing;
    }

    // Cap keys in wire order (index = idx); SQLite row order isn't guaranteed.
    public String[] getCaps(String deviceId) {
        MeoDeviceCap[] rows = dao.query(MeoDeviceCap.class, "deviceId", deviceId);
        if (rows == null) {
            return new String[0];
        }
        return Arrays.stream(rows)
                .sorted(Comparator.comparingInt(MeoDeviceCap::getIdx))
                .map(MeoDeviceCap::getCap)
                .toArray(String[]::new);
    }
}
