package org.thingai.app.meo.localapi.route;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import org.thingai.app.meo.localapi.dto.DeviceResponse;
import org.thingai.app.meo.localapi.dto.ErrorResponse;
import org.thingai.app.meo.define.MeoErr;
import org.thingai.app.meo.entity.MeoDevice;
import org.thingai.app.meo.handler.mngt.MeoMngtHandler;

// Lists, edits user metadata on, and removes devices created by ProvisionRoute.
public class DeviceRoute {
    private final MeoMngtHandler deviceHandler;

    public DeviceRoute(MeoMngtHandler deviceHandler) {
        this.deviceHandler = deviceHandler;
    }

    public void addRoutes(JavalinConfig config) {
        config.routes.get("/api/v1/devices", this::list);
        config.routes.get("/api/v1/devices/{deviceId}", this::get);
        config.routes.put("/api/v1/devices/{deviceId}", this::update);
        config.routes.delete("/api/v1/devices/{deviceId}", this::delete);
    }

    private void list(Context ctx) {
        MeoDevice[] devices = deviceHandler.getDevices();
        DeviceResponse[] response = new DeviceResponse[devices.length];
        for (int i = 0; i < devices.length; i++) {
            response[i] = toResponse(devices[i]);
        }
        ctx.json(response);
    }

    private void get(Context ctx) {
        MeoDevice device = deviceHandler.getDevice(ctx.pathParam("deviceId"));
        if (device == null) {
            notFound(ctx);
            return;
        }
        ctx.json(toResponse(device));
    }

    private void update(Context ctx) {
        MeoDevice update = ctx.bodyAsClass(MeoDevice.class);
        if (update == null) {
            ctx.status(400).json(new ErrorResponse(MeoErr.DEVICE_UPDATE_FAILED, "request body is required"));
            return;
        }
        MeoDevice device = deviceHandler.updateDevice(ctx.pathParam("deviceId"), update);
        if (device == null) {
            notFound(ctx);
            return;
        }
        ctx.json(toResponse(device));
    }

    private void delete(Context ctx) {
        // Capture caps before the handler removes their rows.
        String deviceId = ctx.pathParam("deviceId");
        String[] caps = deviceHandler.getCaps(deviceId);
        int[] capTypes = deviceHandler.getCapTypes(deviceId);
        MeoDevice device = deviceHandler.deleteDevice(deviceId);
        if (device == null) {
            notFound(ctx);
            return;
        }
        ctx.json(DeviceResponse.of(device, caps, capTypes));
    }

    private DeviceResponse toResponse(MeoDevice device) {
        return DeviceResponse.of(device, deviceHandler.getCaps(device.getDeviceId()),
                deviceHandler.getCapTypes(device.getDeviceId()));
    }

    private void notFound(Context ctx) {
        ctx.status(404).json(new ErrorResponse(MeoErr.DEVICE_NOT_FOUND, "device not found"));
    }
}
