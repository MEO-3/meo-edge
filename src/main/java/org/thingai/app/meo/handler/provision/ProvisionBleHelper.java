package org.thingai.app.meo.handler.provision;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.thingai.app.meo.blemqtt.BlemqttClient;
import org.thingai.app.meo.blemqtt.BlemqttCommand;
import org.thingai.app.meo.blemqtt.BlemqttError;
import org.thingai.app.meo.blemqtt.BlemqttOp;
import org.thingai.app.meo.blemqtt.BlemqttReply;
import org.thingai.app.meo.entity.MeoDeviceProvision;
import org.thingai.app.meo.util.JsonUtil;
import org.thingai.base.log.ILog;

import java.util.concurrent.ExecutionException;

// blemqtt plumbing for provisioning: send a command, build GATT params, read replies and events.
final class ProvisionBleHelper {
    private static final String TAG = "ProvisionBleHelper";
    static final String DEFAULT_ENCODING = "utf8";

    private ProvisionBleHelper() {
    }

    // Blocks for the reply; throws on failure. blemqtt applies its own request timeout.
    static BlemqttReply sendBlocking(BlemqttClient client, BlemqttCommand command) {
        ILog.d(TAG, "send", command.getOp(), command.getRequestId());
        try {
            BlemqttReply reply = client.send(command).get();
            if (!reply.isOk()) {
                throw toException(reply);
            }
            return reply;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while sending " + command.getOp(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw cause instanceof RuntimeException ? (RuntimeException) cause : new RuntimeException(cause);
        }
    }

    static BlemqttCommand gattRead(MeoDeviceProvision provision, String characteristicUuid) {
        JsonObject params = gattParams(provision, characteristicUuid);
        params.addProperty("encoding", DEFAULT_ENCODING);
        return BlemqttCommand.create(BlemqttOp.GATT_READ, params);
    }

    static JsonObject gattParams(MeoDeviceProvision provision, String characteristicUuid) {
        JsonObject params = addressParams(provision);
        params.addProperty("serviceUuid", ProvisionBleUuid.MEO_DEVICE_PROVISION_SERVICE);
        params.addProperty("characteristicUuid", characteristicUuid);
        return params;
    }

    static JsonObject addressParams(MeoDeviceProvision provision) {
        JsonObject params = new JsonObject();
        params.addProperty("address", provision.getBleAddress());
        return params;
    }

    // Transport cleanup only — does not touch provisioning status, which persistDevice needs.
    static void safeDisconnect(BlemqttClient client, MeoDeviceProvision provision) {
        try {
            sendBlocking(client, BlemqttCommand.create(BlemqttOp.DEVICE_DISCONNECT, addressParams(provision)));
            ILog.i(TAG, "disconnect", "disconnected", addressLog(provision));
        } catch (RuntimeException e) {
            ILog.w(TAG, "disconnect failed", e);
        }
    }

    static String addressLog(MeoDeviceProvision provision) {
        if (provision == null) {
            return "bleAddress=null";
        }
        return "bleAddress=" + provision.getBleAddress();
    }

    private static RuntimeException toException(BlemqttReply reply) {
        BlemqttError error = reply.getError();
        if (error == null) {
            return new RuntimeException("blemqtt command failed");
        }
        return new RuntimeException(error.getCode() + ": " + error.getMessage());
    }

    // True if field is absent or equals expected (case-insensitive); absent = not a mismatch.
    static boolean matches(JsonObject object, String field, String expected) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull() || expected == null) {
            return true;
        }
        return expected.equalsIgnoreCase(value.getAsString());
    }

    static String readReplyValue(BlemqttReply reply) {
        JsonElement result = reply.getResult();
        if (result == null || result.isJsonNull()) {
            return "";
        }
        if (result.isJsonPrimitive()) {
            return result.getAsString();
        }
        if (!result.isJsonObject()) {
            return result.toString();
        }

        JsonObject object = result.getAsJsonObject();
        String[] fields = {"value", "macAddress", "mac", "status", "state", "message"};
        for (String field : fields) {
            JsonElement value = object.get(field);
            if (value != null && !value.isJsonNull()) {
                return value.isJsonPrimitive() ? value.getAsString() : value.toString();
            }
        }
        return object.toString();
    }

    static JsonObject[] parseScanDevices(BlemqttReply reply) {
        JsonElement result = reply.getResult();
        if (result == null || !result.isJsonObject()) {
            return new JsonObject[0];
        }
        JsonElement devices = result.getAsJsonObject().get("devices");
        if (devices == null || !devices.isJsonArray()) {
            return new JsonObject[0];
        }
        JsonObject[] parsed = JsonUtil.fromJson(devices.toString(), JsonObject[].class);
        return parsed != null ? parsed : new JsonObject[0];
    }
}
