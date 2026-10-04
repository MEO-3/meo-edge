package org.thingai.app.meo.localapi.route;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import org.thingai.app.meo.localapi.dto.CommandRequest;
import org.thingai.app.meo.localapi.dto.CommandResponse;
import org.thingai.app.meo.localapi.dto.ErrorResponse;
import org.thingai.app.meo.callback.RequestCallback;
import org.thingai.app.meo.define.MeoEdgeMsgOpcode;
import org.thingai.app.meo.define.MeoErr;
import org.thingai.app.meo.handler.msg.MeoMsgHandler;

import java.util.concurrent.CompletableFuture;

// Device control endpoint: read or write a device cap, get the device's value back.
public class ControlRoute {
    // Null when device MQTT failed to connect at startup; answer 503 instead of throwing.
    private final MeoMsgHandler msgHandler;

    public ControlRoute(MeoMsgHandler msgHandler) {
        this.msgHandler = msgHandler;
    }

    public void addRoutes(JavalinConfig config) {
        config.routes.post("/api/v1/devices/{deviceId}/command", this::command);
    }

    private void command(Context ctx) {
        if (msgHandler == null) {
            fail(ctx, MeoErr.MSG_SEND_FAILED, "device messaging is not connected", 503);
            return;
        }
        CommandRequest request = ctx.bodyAsClass(CommandRequest.class);
        if (request == null) {
            fail(ctx, MeoErr.BAD_REQUEST, "request body is required", 400);
            return;
        }

        String deviceId = ctx.pathParam("deviceId");
        String cap = request.getCap();
        // sendDown is async; ctx.future keeps the request open until the callback answers.
        CompletableFuture<Void> done = new CompletableFuture<>();
        ctx.future(() -> done);
        msgHandler.sendDown(deviceId, cap, toOp(request.getOp()), request.getValue(), new RequestCallback<Integer>() {
            @Override
            public void onResult(Integer value, String message) {
                ctx.json(CommandResponse.of(deviceId, cap, value));
                done.complete(null);
            }

            @Override
            public void onFailure(int errorCode, String message) {
                fail(ctx, errorCode, message, statusFor(errorCode));
                done.complete(null);
            }
        });
    }

    // Unknown op maps to -1, which the handler rejects.
    private int toOp(String op) {
        if ("read".equals(op)) {
            return MeoEdgeMsgOpcode.READ;
        }
        if ("write".equals(op)) {
            return MeoEdgeMsgOpcode.WRITE;
        }
        return -1;
    }

    private int statusFor(int errorCode) {
        if (errorCode == MeoErr.DEVICE_NOT_FOUND) {
            return 404;
        }
        if (errorCode == MeoErr.MSG_TIMEOUT) {
            return 504;
        }
        if (errorCode == MeoErr.HANDLE_FAILED || errorCode == MeoErr.MSG_SEND_FAILED) {
            return 502;
        }
        return 400;
    }

    private void fail(Context ctx, int errorCode, String message, int status) {
        ctx.status(status).json(new ErrorResponse(errorCode, message));
    }
}
