package org.thingai.app.meo.handler.provision;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.thingai.app.meo.blemqtt.BlemqttClient;
import org.thingai.app.meo.blemqtt.BlemqttCommand;
import org.thingai.app.meo.blemqtt.BlemqttConfig;
import org.thingai.app.meo.blemqtt.BlemqttError;
import org.thingai.app.meo.blemqtt.BlemqttEvent;
import org.thingai.app.meo.blemqtt.BlemqttOp;
import org.thingai.app.meo.blemqtt.BlemqttReply;
import org.thingai.app.meo.define.MeoErr;
import org.thingai.app.meo.define.MeoDevProvisionStatus;
import org.thingai.app.meo.define.MeoDevTransportType;
import org.thingai.app.meo.localapi.dto.DeviceResponse;
import org.thingai.app.meo.entity.MeoDevice;
import org.thingai.app.meo.entity.MeoDeviceCap;
import org.thingai.app.meo.entity.MeoDeviceProvision;
import org.thingai.app.meo.handler.msg.EdgeMsgDto;
import org.thingai.app.meo.callback.ProvisionEventListener;
import org.thingai.app.meo.callback.RequestCallback;
import org.thingai.app.meo.util.JsonUtil;
import org.thingai.app.meo.util.NetUtil;
import org.thingai.base.dao.Dao;
import org.thingai.base.log.ILog;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

// Gateway-led BLE provisioning. Methods block on blemqtt replies, except
// setupDevice, which polls for the async gatt.notification Wi-Fi join event.
public class MeoProvisionHandler {
    private static final String TAG = "MeoProvisionHandler";
    private static final String DEFAULT_ENCODING = "utf8";
    private static final String EVENT_GATT_NOTIFICATION = "gatt.notification";
    private static final String STATE_CONNECTED = "connected";
    private static final String STATE_FAILED = "failed";

    // Progress events pushed to the registered listener (the SSE endpoint).
    public static final String EVENT_PROVISION_STATUS = "provision.status";
    public static final String EVENT_SCAN_STARTED = "scan.started";
    public static final String EVENT_SCAN_DEVICE_FOUND = "scan.device_found";
    public static final String EVENT_SCAN_COMPLETED = "scan.completed";
    public static final String EVENT_DEVICE_PERSISTED = "device.persisted";

    // Max wait for a terminal Wi-Fi join state after credentials are written.
    private static final long WIFI_JOIN_TIMEOUT_MS = 45_000;

    // Mosquitto default port; broker host is the gateway's LAN IPv4, resolved per call.
    private static final int DEVICE_BROKER_PORT = 1883;
    // Device-defined cap key: lowercase so keys can't clash by case, safe in topics/URLs.
    private static final Pattern CAP_KEY = Pattern.compile("[a-z0-9_]{1,32}");

    private final BlemqttClient blemqttClient;
    private final Dao dao;

    // Single in-flight session; BLE is one device at a time. Null when idle.
    private MeoDeviceProvision session;

    // Optional SSE observer; emits are fire-and-forget, called from request or MQTT threads.
    private volatile ProvisionEventListener eventListener;

    public MeoProvisionHandler(String brokerUrl, Dao dao) {
        BlemqttConfig config = new BlemqttConfig();
        config.setBrokerUrl(brokerUrl);
        this.blemqttClient = new BlemqttClient(config);
        this.dao = dao;
    }

    public void start() {
        try {
            blemqttClient.connect();
            ILog.d(TAG, "blemqtt connect");
        } catch (Exception e) {
            ILog.e(TAG, "blemqtt connect failed", e);
            throw new RuntimeException(e);
        }
    }

    public void stop() {
        try {
            blemqttClient.disconnect();
        } catch (Exception e) {
            ILog.w(TAG, "blemqtt disconnect failed", e);
        }
    }

    public void setEventListener(ProvisionEventListener listener) {
        this.eventListener = listener;
    }

    // Snapshot of the in-flight session for late/reconnecting SSE clients.
    public synchronized MeoDeviceProvision currentSession() {
        return session;
    }

    // Blocks on scan.start's reply for ~timeoutMs; keep timeoutMs under the blemqtt
    // 15s reply timeout. Synchronized like the other steps — BLE is one radio.
    public synchronized void scan(int timeoutMs, String namePrefix, RequestCallback<JsonObject[]> callback) {
        ILog.i(TAG, "scan", "timeoutMs=" + timeoutMs, "namePrefix=" + namePrefix);

        JsonObject params = new JsonObject();
        params.addProperty("timeoutMs", timeoutMs);
        params.addProperty("serviceUuid", ProvisionBleUuid.MEO_DEVICE_PROVISION_SERVICE);
        if (namePrefix != null && !namePrefix.isEmpty()) {
            params.addProperty("namePrefix", namePrefix);
        }

        emit(EVENT_SCAN_STARTED, params);
        try {
            BlemqttReply reply = sendBlocking(BlemqttCommand.create(BlemqttOp.SCAN_START, params));
            JsonObject[] devices = parseScanDevices(reply);
            for (JsonObject device : devices) {
                emit(EVENT_SCAN_DEVICE_FOUND, device);
            }
            ILog.i(TAG, "scan complete", "count=" + devices.length);
            emit(EVENT_SCAN_COMPLETED, devices);
            callback.onResult(devices, "scan complete");
        } catch (RuntimeException e) {
            ILog.e(TAG, "scan failed", e);
            callback.onFailure(MeoErr.PROV_SCAN_FAILED, failureMessage(e, "scan failed"));
        }
    }

    private JsonObject[] parseScanDevices(BlemqttReply reply) {
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

    // Connects, reads identity/caps, and opens the session for setupDevice.
    // Reclaims any prior unfinished session first, since BLE is single-device.
    public synchronized void connect(String bleAddress, RequestCallback<MeoDeviceProvision> callback) {
        ILog.i(TAG, "connect", "bleAddress=" + bleAddress);
        if (isEmpty(bleAddress)) {
            callback.onFailure(MeoErr.PROV_CONNECT_FAILED, "ble address is required");
            return;
        }
        reset();

        MeoDeviceProvision provision = new MeoDeviceProvision();
        provision.setBleAddress(bleAddress);
        try {
            bleConnect(provision);
            readMac(provision);
            readCaps(provision);
            updateStatus(provision, MeoDevProvisionStatus.STATUS_CONNECTED_BLE, "device connected");
            session = provision;
            callback.onResult(provision, "device connected");
        } catch (RuntimeException e) {
            ILog.e(TAG, "connect failed", e);
            updateStatus(provision, MeoDevProvisionStatus.STATUS_FAILED, failureMessage(e, "connect failed"));
            safeDisconnect(provision);
            callback.onFailure(MeoErr.PROV_CONNECT_FAILED, failureMessage(e, "connect failed"));
        }
    }

    // Writes Wi-Fi + broker config and waits for the device to join. Requires an
    // open session from connect(). On failure BLE stays connected so the client can retry.
    public synchronized void setupDevice(String ssid, String password, RequestCallback<MeoDeviceProvision> callback) {
        ILog.i(TAG, "setupDevice", addressLog(session));
        if (session == null) {
            callback.onFailure(MeoErr.PROV_SETUP_FAILED, "no device connected; call connect first");
            return;
        }
        if (isEmpty(ssid)) {
            callback.onFailure(MeoErr.PROV_SETUP_FAILED, "wifi ssid is required");
            return;
        }
        String brokerHost = NetUtil.lanIpv4();
        if (isEmpty(brokerHost)) {
            callback.onFailure(MeoErr.PROV_SETUP_FAILED, "cannot determine gateway LAN IPv4");
            return;
        }

        MeoDeviceProvision current = session;
        BlockingQueue<Object> terminalState = new LinkedBlockingQueue<>();
        blemqttClient.onEvent(event -> onStatusNotification(current, event, terminalState));
        try {
            subscribeStatus(current);
            writeNetworkConfig(current, ssid, password, brokerHost);
            awaitWifiJoin(current, terminalState);
            updateStatus(current, MeoDevProvisionStatus.STATUS_PROVISIONED, "device provisioned");
            safeDisconnect(current);
            callback.onResult(current, "device provisioned");
        } catch (RuntimeException e) {
            ILog.e(TAG, "setupDevice failed", e);
            updateStatus(current, MeoDevProvisionStatus.STATUS_FAILED, failureMessage(e, "setup failed"));
            callback.onFailure(MeoErr.PROV_SETUP_FAILED, failureMessage(e, "setup failed"));
        } finally {
            blemqttClient.removeEventCallback();
        }
    }

    // Persists the session's device + cap rows. Requires setupDevice to have
    // completed (status = provisioned); clears the session on success.
    public synchronized void persistDevice(RequestCallback<DeviceResponse> callback) {
        ILog.i(TAG, "persistDevice", addressLog(session));
        if (session == null) {
            callback.onFailure(MeoErr.PROV_PRESIST_FAILED, "no device connected; call connect first");
            return;
        }
        if (session.getStatus() != MeoDevProvisionStatus.STATUS_PROVISIONED) {
            callback.onFailure(MeoErr.PROV_PRESIST_FAILED, "device not set up; call setupDevice first");
            return;
        }

        MeoDeviceProvision current = session;
        try {
            MeoDevice device = saveDevice(current);
            persistCaps(device.getDeviceId(), current.getCaps());
            session = null;
            DeviceResponse response = DeviceResponse.of(device, current.getCaps());
            emit(EVENT_DEVICE_PERSISTED, response);
            callback.onResult(response, "device persisted");
        } catch (RuntimeException e) {
            ILog.e(TAG, "persistDevice failed", e);
            callback.onFailure(MeoErr.PROV_PRESIST_FAILED, failureMessage(e, "persist failed"));
        }
    }

    // Releases any in-flight session's BLE link; BLE is single-device.
    private void reset() {
        if (session != null) {
            safeDisconnect(session);
            session = null;
        }
    }

    // Upserts the device row. deviceId is the topic-ready MAC, macAddress the readable one.
    private MeoDevice saveDevice(MeoDeviceProvision provision) {
        if (isEmpty(provision.getMacAddress())) {
            throw new IllegalStateException("device MAC is required to persist device");
        }
        MeoDevice device = new MeoDevice();
        device.setDeviceId(normalizeDeviceId(provision.getMacAddress()));
        device.setMacAddress(provision.getMacAddress());
        device.setTransportType(MeoDevTransportType.WIFI_LAN);
        device.setModel(provision.getModel());
        device.setFwVersion(provision.getFwVersion());

        dao.insertOrUpdate(device);
        ILog.i(TAG, "persistDevice", "persisted deviceId=" + device.getDeviceId());
        return device;
    }

    // Upserts the device's cap row so re-provisioning replaces the list, not accumulates.
    private void persistCaps(String deviceId, String[] caps) {
        MeoDeviceCap row = new MeoDeviceCap();
        row.setDeviceId(deviceId);
        row.setCaps(JsonUtil.toJson(caps != null ? caps : new String[0]));
        dao.insertOrUpdate(row);
        ILog.i(TAG, "persistCaps", "deviceId=" + deviceId, "count=" + (caps != null ? caps.length : 0));
    }

    // Sets status/message, then emits the session as a provision.status event.
    private void updateStatus(MeoDeviceProvision provision, int status, String message) {
        provision.setStatus(status);
        if (message != null) {
            provision.setMessage(message);
        }
        emit(EVENT_PROVISION_STATUS, provision);
    }

    private void emit(String event, Object payload) {
        ProvisionEventListener listener = eventListener;
        if (listener == null) {
            return;
        }
        try {
            listener.onEvent(event, payload);
        } catch (RuntimeException e) {
            ILog.w(TAG, "emit", "event listener failed", e.getMessage());
        }
    }

    // --- Steps ----------------------------------------------------------------

    private void bleConnect(MeoDeviceProvision provision) {
        updateStatus(provision, MeoDevProvisionStatus.STATUS_CONNECTING_BLE, null);
        sendBlocking(BlemqttCommand.create(BlemqttOp.DEVICE_CONNECT, addressParams(provision)));
        ILog.i(TAG, "connect", "connected", addressLog(provision));
    }

    private void readMac(MeoDeviceProvision provision) {
        updateStatus(provision, MeoDevProvisionStatus.STATUS_READING_MAC, null);
        String mac = readReplyValue(sendBlocking(gattRead(provision, ProvisionBleUuid.MEO_DEVICE_MAC_CHAR)));
        provision.setMacAddress(mac);
        ILog.i(TAG, "readDeviceMac", "macAddress=" + mac);
    }

    // Non-fatal: read/parse failure leaves an empty cap set — the device is still
    // usable on Wi-Fi and re-provisioning refreshes it.
    private void readCaps(MeoDeviceProvision provision) {
        updateStatus(provision, MeoDevProvisionStatus.STATUS_READING_CAPABILITIES, null);
        try {
            String raw = readReplyValue(sendBlocking(gattRead(provision, ProvisionBleUuid.MEO_DEVICE_CAPABILITIES_CHAR)));
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
            ILog.i(TAG, "readCaps", "model=" + provision.getModel(),
                    "fw=" + provision.getFwVersion(), "count=" + provision.getCaps().length);
        } catch (RuntimeException e) {
            provision.setCaps(new String[0]);
            ILog.w(TAG, "readCaps", "failed; continuing with empty caps", e.getMessage());
        }
    }

    // Keys become topic/URL segments and array position is the wire idx, so a bad or
    // duplicate key rejects the whole report rather than shifting the other indexes.
    private String[] parseCaps(JsonElement element) {
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

    private void subscribeStatus(MeoDeviceProvision provision) {
        sendBlocking(BlemqttCommand.create(BlemqttOp.GATT_SUBSCRIBE, gattParams(provision, ProvisionBleUuid.MEO_PROVISION_STATUS_CHAR)));
        ILog.i(TAG, "subscribeStatus", "subscribed", addressLog(provision));
    }

    private void writeNetworkConfig(MeoDeviceProvision provision, String ssid, String password, String brokerHost) {
        JsonObject networkConfig = new JsonObject();
        networkConfig.addProperty("ssid", ssid);
        networkConfig.addProperty("password", password != null ? password : "");
        networkConfig.addProperty("brokerHost", brokerHost);
        networkConfig.addProperty("brokerPort", DEVICE_BROKER_PORT);

        JsonObject params = gattParams(provision, ProvisionBleUuid.MEO_NETWORK_CONFIG_CHAR);
        params.addProperty("encoding", DEFAULT_ENCODING);
        params.addProperty("value", JsonUtil.toJson(networkConfig));

        provision.setWifiSsid(ssid);
        updateStatus(provision, MeoDevProvisionStatus.STATUS_WRITING_WIFI, null);
        sendBlocking(BlemqttCommand.create(BlemqttOp.GATT_WRITE, params));
        updateStatus(provision, MeoDevProvisionStatus.STATUS_WRITING_WIFI, "network config written");
        ILog.i(TAG, "writeNetworkConfig", "written", "ssid=" + ssid,
                "broker=" + brokerHost + ":" + DEVICE_BROKER_PORT);
    }

    // Block until the device reports a terminal Wi-Fi state or the timeout hits.
    private void awaitWifiJoin(MeoDeviceProvision provision, BlockingQueue<Object> terminalState) {
        updateStatus(provision, MeoDevProvisionStatus.STATUS_READING_STATUS, null);
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

    // Transport cleanup only — does not touch provisioning status, which persistDevice needs.
    private void safeDisconnect(MeoDeviceProvision provision) {
        try {
            sendBlocking(BlemqttCommand.create(BlemqttOp.DEVICE_DISCONNECT, addressParams(provision)));
            ILog.i(TAG, "disconnect", "disconnected", addressLog(provision));
        } catch (RuntimeException e) {
            ILog.w(TAG, "disconnect failed", e);
        }
    }

    // --- Events ---------------------------------------------------------------

    private void onStatusNotification(MeoDeviceProvision provision, BlemqttEvent event, BlockingQueue<Object> terminalState) {
        if (event == null || !EVENT_GATT_NOTIFICATION.equals(event.getEventType())) {
            return;
        }
        JsonElement payload = event.getPayload();
        if (payload == null || !payload.isJsonObject()) {
            return;
        }
        JsonObject notification = payload.getAsJsonObject();
        if (!matches(notification, "address", provision.getBleAddress())
                || !matches(notification, "characteristicUuid", ProvisionBleUuid.MEO_PROVISION_STATUS_CHAR)) {
            return;
        }

        String state = extractState(notification.get("value"));
        if (state == null) {
            return;
        }
        provision.setProvisionStatus(state);
        provision.setMessage(state);
        ILog.i(TAG, "status", state, addressLog(provision));
        emit(EVENT_PROVISION_STATUS, provision);

        if (STATE_CONNECTED.equalsIgnoreCase(state)) {
            terminalState.offer(Boolean.TRUE);
        } else if (STATE_FAILED.equalsIgnoreCase(state)) {
            terminalState.offer(new RuntimeException("device reported Wi-Fi join failed"));
        }
    }

    // --- blemqtt helpers ------------------------------------------------------

    // Blocks for the reply; throws on failure. blemqtt applies its own request timeout.
    private BlemqttReply sendBlocking(BlemqttCommand command) {
        ILog.d(TAG, "send", command.getOp(), command.getRequestId());
        try {
            BlemqttReply reply = blemqttClient.send(command).get();
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

    private BlemqttCommand gattRead(MeoDeviceProvision provision, String characteristicUuid) {
        JsonObject params = gattParams(provision, characteristicUuid);
        params.addProperty("encoding", DEFAULT_ENCODING);
        return BlemqttCommand.create(BlemqttOp.GATT_READ, params);
    }

    private JsonObject gattParams(MeoDeviceProvision provision, String characteristicUuid) {
        JsonObject params = addressParams(provision);
        params.addProperty("serviceUuid", ProvisionBleUuid.MEO_DEVICE_PROVISION_SERVICE);
        params.addProperty("characteristicUuid", characteristicUuid);
        return params;
    }

    private JsonObject addressParams(MeoDeviceProvision provision) {
        JsonObject params = new JsonObject();
        params.addProperty("address", provision.getBleAddress());
        return params;
    }

    private RuntimeException toException(BlemqttReply reply) {
        BlemqttError error = reply.getError();
        if (error == null) {
            return new RuntimeException("blemqtt command failed");
        }
        return new RuntimeException(error.getCode() + ": " + error.getMessage());
    }

    // True if field is absent or equals expected (case-insensitive); absent = not a mismatch.
    private boolean matches(JsonObject object, String field, String expected) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull() || expected == null) {
            return true;
        }
        return expected.equalsIgnoreCase(value.getAsString());
    }

    private String extractState(JsonElement valueElement) {
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

    private String readReplyValue(BlemqttReply reply) {
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

    // AA:BB:CC:DD:EE:FF -> aabbccddeeff, the form firmware uses in MQTT topics.
    private static String normalizeDeviceId(String macAddress) {
        return macAddress.replace(":", "").toLowerCase();
    }

    private String failureMessage(Throwable t, String fallback) {
        return t.getMessage() != null ? t.getMessage() : fallback;
    }

    private String addressLog(MeoDeviceProvision provision) {
        if (provision == null) {
            return "bleAddress=null";
        }
        return "bleAddress=" + provision.getBleAddress();
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
