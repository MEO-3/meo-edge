package org.thingai.app.meo.handler.provision;

import com.google.gson.JsonObject;
import org.thingai.app.meo.blemqtt.*;
import org.thingai.app.meo.define.MeoErr;
import org.thingai.app.meo.define.MeoDevProvisionStatus;
import org.thingai.app.meo.define.MeoDevTransportType;
import org.thingai.app.meo.localapi.dto.DeviceResponse;
import org.thingai.app.meo.entity.MeoDevice;
import org.thingai.app.meo.entity.MeoDeviceCap;
import org.thingai.app.meo.entity.MeoDeviceProvision;
import org.thingai.app.meo.callback.ProvisionEventListener;
import org.thingai.app.meo.callback.RequestCallback;
import org.thingai.app.meo.util.JsonUtil;
import org.thingai.base.dao.Dao;
import org.thingai.base.log.ILog;
import org.thingai.base.utils.ArrayUtils;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.thingai.app.meo.handler.provision.ProvisionBleHelper.*;
    
public class MeoProvisionHandler {
    private static final String TAG = "MeoProvisionHandler";

    private final BlemqttClient blemqttClient;
    private final Dao dao;

    // device provision buffer, allow only one device provision at a time.
    private MeoDeviceProvision session;

    private volatile ProvisionEventListener[] eventListeners = new ProvisionEventListener[0];

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

    public synchronized void addEventListener(ProvisionEventListener listener) {
        if (listener == null) {
            return;
        }
        eventListeners = ArrayUtils.append(eventListeners, listener);
    }

    public synchronized void removeEventListener(ProvisionEventListener listener) {
        if (listener == null) {
            return;
        }
        int idx = ArrayUtils.indexOf(eventListeners, listener);
        if (idx >= 0) {
            eventListeners = ArrayUtils.removeAt(eventListeners, idx);
        }
    }

    public synchronized MeoDeviceProvision getCurrentDevProvision() {
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

        emit(ProvisionEvent.SCAN_STARTED, params);
        try {
            BlemqttReply reply = sendBlocking(blemqttClient, BlemqttCommand.create(BlemqttOp.SCAN_START, params));
            JsonObject[] devices = parseScanDevices(reply);
            for (JsonObject device : devices) {
                emit(ProvisionEvent.SCAN_DEVICE_FOUND, device);
            }
            ILog.i(TAG, "scan complete", "count=" + devices.length);
            emit(ProvisionEvent.SCAN_COMPLETED, devices);
            callback.onResult(devices, "scan complete");
        } catch (RuntimeException e) {
            ILog.e(TAG, "scan failed", e);
            callback.onFailure(MeoErr.PROV_SCAN_FAILED, failureMessage(e, "scan failed"));
        }
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
            updateStatus(provision, MeoDevProvisionStatus.STATUS_CONNECTING_BLE, null);
            ProvisionStepConnect.bleConnect(blemqttClient, provision);
            updateStatus(provision, MeoDevProvisionStatus.STATUS_READING_MAC, null);
            ProvisionStepConnect.readMac(blemqttClient, provision);
            updateStatus(provision, MeoDevProvisionStatus.STATUS_READING_CAPABILITIES, null);
            ProvisionStepConnect.readCaps(blemqttClient, provision);
            updateStatus(provision, MeoDevProvisionStatus.STATUS_CONNECTED_BLE, "device connected");
            session = provision;
            callback.onResult(provision, "device connected");
        } catch (RuntimeException e) {
            ILog.e(TAG, "connect failed", e);
            updateStatus(provision, MeoDevProvisionStatus.STATUS_FAILED, failureMessage(e, "connect failed"));
            safeDisconnect(blemqttClient, provision);
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
        String brokerHost = ProvisionConfig.DEFAULT_BROKER_HOST;
        if (isEmpty(brokerHost)) {
            callback.onFailure(MeoErr.PROV_SETUP_FAILED, "cannot determine gateway LAN IPv4");
            return;
        }

        MeoDeviceProvision current = session;
        BlockingQueue<Object> terminalState = new LinkedBlockingQueue<>();
        blemqttClient.onEvent(new BlemqttCallback<BlemqttEvent>() {
            @Override
            public void handle(BlemqttEvent value) {
                onStatusNotification(current, value, terminalState);
            }
        });

        try {
            ProvisionStepSetup.subscribeStatus(blemqttClient, current);

            current.setWifiSsid(ssid);
            updateStatus(current, MeoDevProvisionStatus.STATUS_WRITING_WIFI, null);
            ProvisionStepSetup.writeNetworkConfig(blemqttClient, current, ssid, password, brokerHost);
            updateStatus(current, MeoDevProvisionStatus.STATUS_WRITING_WIFI, "network config written");
            updateStatus(current, MeoDevProvisionStatus.STATUS_READING_STATUS, null);
            ProvisionStepSetup.awaitWifiJoin(terminalState);
            updateStatus(current, MeoDevProvisionStatus.STATUS_PROVISIONED, "device provisioned");
            safeDisconnect(blemqttClient, current);
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
            saveDeviceCaps(device.getDeviceId(), current.getCaps(), current.getCapTypes());
            session = null;
            DeviceResponse response = DeviceResponse.of(device, current.getCaps(), current.getCapTypes());
            emit(ProvisionEvent.DEVICE_PERSISTED, response);
            callback.onResult(response, "device persisted");
        } catch (RuntimeException e) {
            ILog.e(TAG, "persistDevice failed", e);
            callback.onFailure(MeoErr.PROV_PRESIST_FAILED, failureMessage(e, "persist failed"));
        }
    }

    // Releases any in-flight session's BLE link; BLE is single-device.
    private void reset() {
        if (session != null) {
            safeDisconnect(blemqttClient, session);
            session = null;
        }
    }

    // Upserts the device row. deviceId is the topic-ready MAC, macAddress the readable one.
    private MeoDevice saveDevice(MeoDeviceProvision provision) {
        if (isEmpty(provision.getMacAddress())) {
            throw new IllegalStateException("device MAC is required to persist device");
        }
        MeoDevice device = new MeoDevice();
        device.setDeviceId(normalizeDevId(provision.getMacAddress()));
        device.setMacAddress(provision.getMacAddress());
        device.setTransportType(MeoDevTransportType.WIFI_LAN);
        device.setModel(provision.getModel());
        device.setFwVersion(provision.getFwVersion());

        dao.insertOrUpdate(device);
        ILog.i(TAG, "persistDevice", "persisted deviceId=" + device.getDeviceId());
        return device;
    }

    // Upserts the device's cap row so re-provisioning replaces the list, not accumulates.
    private void saveDeviceCaps(String deviceId, String[] caps, int[] capTypes) {
        MeoDeviceCap row = new MeoDeviceCap();
        row.setDeviceId(deviceId);
        row.setCaps(JsonUtil.toJson(caps != null ? caps : new String[0]));
        row.setTypes(JsonUtil.toJson(capTypes != null ? capTypes : new int[0]));
        dao.insertOrUpdate(row);
        ILog.i(TAG, "saveDeviceCaps", "deviceId=" + deviceId, "count=" + (caps != null ? caps.length : 0));
    }

    // Sets status/message, then emits the session as a provision.status event.
    private void updateStatus(MeoDeviceProvision provision, int status, String message) {
        provision.setStatus(status);
        if (message != null) {
            provision.setMessage(message);
        }
        emit(ProvisionEvent.PROVISION_STATUS, provision);
    }

    private void emit(String event, Object payload) {
        for (ProvisionEventListener eventListener : eventListeners) {
            try {
                eventListener.onEvent(event, payload);
            } catch (RuntimeException e) {
                ILog.w(TAG, "emit", "listener failed", e);
            }
        }
    }

    // Applies the device's Wi-Fi state to the session and wakes setupDevice on a terminal one.
    private void onStatusNotification(MeoDeviceProvision provision, BlemqttEvent event, BlockingQueue<Object> terminalState) {
        String state = ProvisionStepSetup.parseState(event, provision);
        if (state == null) {
            return;
        }
        provision.setProvisionStatus(state);
        provision.setMessage(state);
        ILog.i(TAG, "status", state, addressLog(provision));
        emit(ProvisionEvent.PROVISION_STATUS, provision);

        if (ProvisionStepSetup.STATE_CONNECTED.equalsIgnoreCase(state)) {
            terminalState.offer(Boolean.TRUE);
        } else if (ProvisionStepSetup.STATE_FAILED.equalsIgnoreCase(state)) {
            terminalState.offer(new RuntimeException("device reported Wi-Fi join failed"));
        }
    }

    // --- Helpers --------------------------------------------------------------

    // AA:BB:CC:DD:EE:FF -> aabbccddeeff, the form firmware uses in MQTT topics.
    private static String normalizeDevId(String macAddress) {
        return macAddress.replace(":", "").toLowerCase();
    }

    private String failureMessage(Throwable t, String fallback) {
        return t.getMessage() != null ? t.getMessage() : fallback;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
