package org.thingai.app.meo.api.route;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import org.thingai.app.meo.api.dto.MeoDeviceResponse;
import org.thingai.app.meo.api.dto.MeoErrorResponse;
import org.thingai.app.meo.define.ErrorCode;
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
        MeoDeviceResponse[] response = new MeoDeviceResponse[devices.length];
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
            ctx.status(400).json(new MeoErrorResponse(ErrorCode.DEVICE_UPDATE_FAILED, "request body is required"));
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
        MeoDevice device = deviceHandler.deleteDevice(deviceId);
        if (device == null) {
            notFound(ctx);
            return;
        }
        ctx.json(MeoDeviceResponse.of(device, caps));
    }

    private MeoDeviceResponse toResponse(MeoDevice device) {
        return MeoDeviceResponse.of(device, deviceHandler.getCaps(device.getDeviceId()));
    }

    private void notFound(Context ctx) {
        ctx.status(404).json(new MeoErrorResponse(ErrorCode.DEVICE_NOT_FOUND, "device not found"));
    }
}
