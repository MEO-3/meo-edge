package org.thingai.app.meo.api.route;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import org.thingai.app.meo.api.dto.MeoCommandRequest;
import org.thingai.app.meo.api.dto.MeoCommandResponse;
import org.thingai.app.meo.api.dto.MeoErrorResponse;
import org.thingai.app.meo.callback.RequestCallback;
import org.thingai.app.meo.define.ErrorCode;
import org.thingai.app.meo.handler.control.MeoControlHandler;
import org.thingai.app.meo.handler.msg.MeoFrame;

// Device control endpoint: read or write a device cap, get the device's value back.
public class ControlRoute {
    // Null when device MQTT failed to connect at startup; answer 503 instead of throwing.
    private final MeoControlHandler controlHandler;

    public ControlRoute(MeoControlHandler controlHandler) {
        this.controlHandler = controlHandler;
    }

    public void addRoutes(JavalinConfig config) {
        config.routes.post("/api/v1/devices/{deviceId}/command", this::command);
    }

    private void command(Context ctx) {
        if (controlHandler == null) {
            fail(ctx, ErrorCode.CONTROL_FAILED, "device messaging is not connected", 503);
            return;
        }
        MeoCommandRequest request = ctx.bodyAsClass(MeoCommandRequest.class);
        if (request == null) {
            fail(ctx, ErrorCode.CONTROL_FAILED, "request body is required", 400);
            return;
        }

        String deviceId = ctx.pathParam("deviceId");
        String cap = request.getCap();
        controlHandler.sendCommand(deviceId, cap, toOp(request.getOp()), request.getValue(), new RequestCallback<Integer>() {
            @Override
            public void onResult(Integer value, String message) {
                ctx.json(MeoCommandResponse.of(deviceId, cap, value));
            }

            @Override
            public void onFailure(int errorCode, String message) {
                fail(ctx, errorCode, message, statusFor(errorCode));
            }
        });
    }

    // Unknown op maps to -1, which the handler rejects.
    private int toOp(String op) {
        if ("read".equals(op)) {
            return MeoFrame.TYPE_READ;
        }
        if ("write".equals(op)) {
            return MeoFrame.TYPE_WRITE;
        }
        return -1;
    }

    private int statusFor(int errorCode) {
        if (errorCode == ErrorCode.DEVICE_NOT_FOUND) {
            return 404;
        }
        if (errorCode == ErrorCode.CONTROL_TIMEOUT) {
            return 504;
        }
        if (errorCode == ErrorCode.CONTROL_DEVICE_ERROR) {
            return 502;
        }
        return 400;
    }

    private void fail(Context ctx, int errorCode, String message, int status) {
        ctx.status(status).json(new MeoErrorResponse(errorCode, message));
    }
}
