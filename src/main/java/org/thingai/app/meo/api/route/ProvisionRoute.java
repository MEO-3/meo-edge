package org.thingai.app.meo.api.route;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import com.google.gson.JsonObject;
import org.thingai.app.meo.api.dto.MeoDeviceResponse;
import org.thingai.app.meo.api.dto.MeoErrorResponse;
import org.thingai.app.meo.api.dto.MeoProvisionRequest;
import org.thingai.app.meo.callback.ProvisionEventListener;
import org.thingai.app.meo.callback.RequestCallback;
import org.thingai.app.meo.define.ErrorCode;
import org.thingai.app.meo.entity.MeoDeviceProvision;
import org.thingai.app.meo.handler.provision.MeoProvisionHandler;
import org.thingai.app.meo.util.JsonUtil;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// Stepped provisioning endpoints (scan -> connect -> setup -> persist) plus an
// SSE stream mirroring the handler's progress events for live UI status.
public class ProvisionRoute implements ProvisionEventListener {
    private static final int DEFAULT_SCAN_TIMEOUT_MS = 8000;

    private final MeoProvisionHandler provisionHandler;

    // Copy-on-write: events fan out from both request threads and the MQTT callback thread.
    private final List<SseClient> sseClients = new CopyOnWriteArrayList<>();

    public ProvisionRoute(MeoProvisionHandler provisionHandler) {
        this.provisionHandler = provisionHandler;
        provisionHandler.setEventListener(this);
    }

    public void addRoutes(JavalinConfig config) {
        config.routes.get("/api/v1/provision/scan", this::scan);
        config.routes.post("/api/v1/provision/connect", this::connect);
        config.routes.post("/api/v1/provision/setup", this::setup);
        config.routes.post("/api/v1/provision/persist", this::persist);
        config.routes.sse("/api/v1/provision/events", this::events);
    }

    private void events(SseClient client) {
        client.keepAlive();
        client.onClose(() -> sseClients.remove(client));
        sseClients.add(client);

        // Send in-flight session state up front for late/reconnecting clients.
        MeoDeviceProvision session = provisionHandler.currentSession();
        if (session != null) {
            send(client, MeoProvisionHandler.EVENT_PROVISION_STATUS, JsonUtil.toJson(session));
        }
    }

    @Override
    public void onEvent(String event, Object payload) {
        if (sseClients.isEmpty()) {
            return;
        }
        String data = JsonUtil.toJson(payload);
        for (SseClient client : sseClients) {
            send(client, event, data);
        }
    }

    private void send(SseClient client, String event, String data) {
        if (client.terminated()) {
            sseClients.remove(client);
            return;
        }
        try {
            client.sendEvent(event, data);
        } catch (RuntimeException e) {
            sseClients.remove(client);
        }
    }

    private void scan(Context ctx) {
        int timeoutMs = parseTimeout(ctx.queryParam("timeoutMs"));
        provisionHandler.scan(timeoutMs, ctx.queryParam("namePrefix"), new RequestCallback<JsonObject[]>() {
            @Override
            public void onResult(JsonObject[] devices, String message) {
                // Gson JsonObjects — Javalin's Jackson mapper can't serialize these.
                ctx.contentType("application/json").result(JsonUtil.toJson(devices));
            }

            @Override
            public void onFailure(int errorCode, String message) {
                fail(ctx, errorCode, message, 500);
            }
        });
    }

    private void connect(Context ctx) {
        MeoProvisionRequest request = ctx.bodyAsClass(MeoProvisionRequest.class);
        if (request == null || isBlank(request.getBleAddress())) {
            ctx.status(400).json(error(ErrorCode.PROV_CONNECT_FAILED, "bleAddress is required"));
            return;
        }
        provisionHandler.connect(request.getBleAddress(), new RequestCallback<MeoDeviceProvision>() {
            @Override
            public void onResult(MeoDeviceProvision provision, String message) {
                ctx.json(provision);
            }

            @Override
            public void onFailure(int errorCode, String message) {
                fail(ctx, errorCode, message, 500);
            }
        });
    }

    private void setup(Context ctx) {
        MeoProvisionRequest request = ctx.bodyAsClass(MeoProvisionRequest.class);
        if (request == null || isBlank(request.getSsid())) {
            ctx.status(400).json(error(ErrorCode.PROV_SETUP_FAILED, "ssid is required"));
            return;
        }
        provisionHandler.setupDevice(request.getSsid(), request.getPassword(), new RequestCallback<MeoDeviceProvision>() {
            @Override
            public void onResult(MeoDeviceProvision provision, String message) {
                ctx.json(provision);
            }

            @Override
            public void onFailure(int errorCode, String message) {
                fail(ctx, errorCode, message, 409);
            }
        });
    }

    private void persist(Context ctx) {
        provisionHandler.persistDevice(new RequestCallback<MeoDeviceResponse>() {
            @Override
            public void onResult(MeoDeviceResponse device, String message) {
                ctx.json(device);
            }

            @Override
            public void onFailure(int errorCode, String message) {
                fail(ctx, errorCode, message, 409);
            }
        });
    }

    private void fail(Context ctx, int errorCode, String message, int status) {
        ctx.status(status).json(error(errorCode, message));
    }

    private MeoErrorResponse error(int errorCode, String message) {
        return new MeoErrorResponse(errorCode, message);
    }

    private int parseTimeout(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return DEFAULT_SCAN_TIMEOUT_MS;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
