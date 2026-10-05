package org.thingai.app.meo.handler.provision;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.thingai.app.meo.blemqtt.BlemqttClient;
import org.thingai.app.meo.blemqtt.BlemqttCommand;
import org.thingai.app.meo.blemqtt.BlemqttEvent;
import org.thingai.app.meo.blemqtt.BlemqttOp;
import org.thingai.app.meo.entity.MeoDeviceProvision;
import org.thingai.app.meo.util.JsonUtil;
import org.thingai.base.log.ILog;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.thingai.app.meo.handler.provision.ProvisionBleHelper.*;

// Setup-phase steps: write Wi-Fi + broker config and wait for the device to join.
// Steps throw on failure and never touch status; the handler owns status and events.
final class ProvisionStepSetup {
    private static final String TAG = "ProvisionStepSetup";
    private static final String EVENT_GATT_NOTIFICATION = "gatt.notification";
    private static final long WIFI_JOIN_TIMEOUT_MS = 45_000;

    static final String STATE_CONNECTED = "connected";
    static final String STATE_FAILED = "failed";

    private ProvisionStepSetup() {
    }

    static void subscribeStatus(BlemqttClient client, MeoDeviceProvision provision) {
        sendBlocking(client, BlemqttCommand.create(BlemqttOp.GATT_SUBSCRIBE, gattParams(provision, ProvisionBleUuid.MEO_PROVISION_STATUS_CHAR)));
        ILog.i(TAG, "subscribeStatus", "subscribed", addressLog(provision));
    }

    static void writeNetworkConfig(BlemqttClient client, MeoDeviceProvision provision, String ssid, String password, String brokerHost) {
        JsonObject networkConfig = new JsonObject();
        networkConfig.addProperty("ssid", ssid);
        networkConfig.addProperty("password", password != null ? password : "");
        networkConfig.addProperty("brokerHost", brokerHost);
        networkConfig.addProperty("brokerPort", ProvisionConfig.DEFAULT_BROKER_PORT);

        JsonObject params = gattParams(provision, ProvisionBleUuid.MEO_NETWORK_CONFIG_CHAR);
        params.addProperty("encoding", DEFAULT_ENCODING);
        params.addProperty("value", JsonUtil.toJson(networkConfig));

        sendBlocking(client, BlemqttCommand.create(BlemqttOp.GATT_WRITE, params));
        ILog.i(TAG, "writeNetworkConfig", "written", "ssid=" + ssid,
                "broker=" + brokerHost + ":" + ProvisionConfig.DEFAULT_BROKER_PORT);
    }

    // Block until the device reports a terminal Wi-Fi state or the timeout hits.
    static void awaitWifiJoin(BlockingQueue<Object> terminalState) {
        Object result;
        try {
            result = terminalState.poll(WIFI_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while waiting for Wi-Fi join", e);
        }
        if (result == null) {
            throw new RuntimeException("timed out waiting for device to join Wi-Fi");
        }
        if (result instanceof Throwable) {
            throw new RuntimeException("device reported Wi-Fi join failed");
        }
    }

    // Returns the Wi-Fi state from this device's status notification, or null for any
    // other event — blemqtt events are shared, so unrelated ones must be ignored.
    static String parseState(BlemqttEvent event, MeoDeviceProvision provision) {
        if (event == null || !EVENT_GATT_NOTIFICATION.equals(event.getEventType())) {
            return null;
        }
        JsonElement payload = event.getPayload();
        if (payload == null || !payload.isJsonObject()) {
            return null;
        }
        JsonObject notification = payload.getAsJsonObject();
        if (!matches(notification, "address", provision.getBleAddress())
                || !matches(notification, "characteristicUuid", ProvisionBleUuid.MEO_PROVISION_STATUS_CHAR)) {
            return null;
        }
        return extractState(notification.get("value"));
    }

    private static String extractState(JsonElement valueElement) {
        if (valueElement == null || valueElement.isJsonNull()) {
            return null;
        }
        try {
            String raw = valueElement.isJsonPrimitive() ? valueElement.getAsString() : valueElement.toString();
            JsonObject stateObject = JsonParser.parseString(raw).getAsJsonObject();
            JsonElement state = stateObject.get("state");
            return state != null && !state.isJsonNull() ? state.getAsString() : null;
        } catch (Exception e) {
            ILog.w(TAG, "extractState", "unparsable status value", valueElement.toString());
            return null;
        }
    }
}
