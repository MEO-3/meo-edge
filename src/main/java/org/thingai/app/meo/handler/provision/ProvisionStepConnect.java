package org.thingai.app.meo.handler.provision;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.thingai.app.meo.blemqtt.BlemqttClient;
import org.thingai.app.meo.blemqtt.BlemqttCommand;
import org.thingai.app.meo.blemqtt.BlemqttOp;
import org.thingai.app.meo.entity.MeoDeviceProvision;
import org.thingai.app.meo.handler.msg.EdgeMsgDto;
import org.thingai.base.log.ILog;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.thingai.app.meo.handler.provision.ProvisionBleHelper.*;

// Connect-phase steps: open the BLE link, read identity and caps.
// Steps throw on failure and never touch status; the handler owns status and events.
final class ProvisionStepConnect {
    private static final String TAG = "ProvisionStepConnect";
    private static final Pattern CAP_KEY = Pattern.compile("[a-z0-9_]{1,32}");

    private ProvisionStepConnect() {
    }

    static void bleConnect(BlemqttClient client, MeoDeviceProvision provision) {
        sendBlocking(client, BlemqttCommand.create(BlemqttOp.DEVICE_CONNECT, addressParams(provision)));
        ILog.i(TAG, "connect", "connected", addressLog(provision));
    }

    static void readMac(BlemqttClient client, MeoDeviceProvision provision) {
        String mac = readReplyValue(sendBlocking(client, gattRead(provision, ProvisionBleUuid.MEO_DEVICE_MAC_CHAR)));
        provision.setMacAddress(mac);
        ILog.i(TAG, "readDeviceMac", "macAddress=" + mac);
    }

    // Non-fatal: read/parse failure leaves an empty cap set — the device is still
    // usable on Wi-Fi and re-provisioning refreshes it.
    static void readCaps(BlemqttClient client, MeoDeviceProvision provision) {
        try {
            String raw = readReplyValue(sendBlocking(client, gattRead(provision, ProvisionBleUuid.MEO_DEVICE_CAPABILITIES_CHAR)));
            JsonObject report = JsonParser.parseString(raw).getAsJsonObject();

            JsonElement model = report.get("model");
            if (model != null && !model.isJsonNull()) {
                provision.setModel(model.getAsString());
            }
            JsonElement fw = report.get("fw");
            if (fw != null && !fw.isJsonNull()) {
                provision.setFwVersion(fw.getAsString());
            }

            provision.setCaps(parseCaps(report.get("caps")));
            provision.setCapTypes(parseCapTypes(report.get("types"), provision.getCaps().length));
            ILog.i(TAG, "readCaps", "model=" + provision.getModel(),
                    "fw=" + provision.getFwVersion(), "count=" + provision.getCaps().length);
        } catch (RuntimeException e) {
            provision.setCaps(new String[0]);
            provision.setCapTypes(new int[0]);
            ILog.w(TAG, "readCaps", "failed; continuing with empty caps", e.getMessage());
        }
    }

    // Keys become topic/URL segments and array position is the wire idx, so a bad or
    // duplicate key rejects the whole report rather than shifting the other indexes.
    static String[] parseCaps(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return new String[0];
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() > EdgeMsgDto.MAX_IDX + 1) {
            throw new IllegalArgumentException("too many caps for a u8 idx: " + array.size());
        }
        String[] caps = new String[array.size()];
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            String cap = array.get(i).getAsString();
            if (!CAP_KEY.matcher(cap).matches() || !seen.add(cap)) {
                throw new IllegalArgumentException("invalid or duplicate cap key: " + cap);
            }
            caps[i] = cap;
        }
        return caps;
    }

    // Types run parallel to caps. Firmware from before types sends none, so a missing or
    // mismatched array falls back to all GENERIC instead of failing the provision.
    static int[] parseCapTypes(JsonElement element, int capCount) {
        int[] types = new int[capCount]; // MeoDevCapabilityType.GENERIC
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != capCount) {
            return types;
        }
        JsonArray array = element.getAsJsonArray();
        try {
            for (int i = 0; i < capCount; i++) {
                types[i] = array.get(i).getAsInt();
            }
        } catch (RuntimeException e) {
            return new int[capCount];
        }
        return types;
    }
}
