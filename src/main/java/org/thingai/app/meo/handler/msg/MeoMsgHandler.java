package org.thingai.app.meo.handler.msg;

import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.thingai.app.meo.callback.RequestCallback;
import org.thingai.app.meo.define.MeoEdgeMsgOpcode;
import org.thingai.app.meo.define.MeoErr;
import org.thingai.app.meo.define.MeoTopic;
import org.thingai.app.meo.entity.MeoDevice;
import org.thingai.app.meo.entity.MeoDeviceCap;
import org.thingai.app.meo.util.JsonUtil;
import org.thingai.base.dao.Dao;
import org.thingai.base.log.ILog;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MeoMsgHandler {
    private static final String TAG = "MeoMsgHandler";

    private static final long REPLY_TIMEOUT_MS = 10_000;
    private static final int DOWN_QOS = 1;
    private static final int UP_QOS = 0;
    private static final long MQTT_SESSION_EXPIRY_SECONDS = 300;

    private final MqttClient mqttClient;
    private final Dao dao;
    private final IMqttMessageListener[] msgListener = new IMqttMessageListener[0];

    private final Map<String, CompletableFuture<EdgeMsgDto>> pendingReplies = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> seqs = new ConcurrentHashMap<>();

    public MeoMsgHandler(String localMqttUrl, Dao dao) throws MqttException {
        this.mqttClient = new MqttClient(
                localMqttUrl,
                "meo-" + System.currentTimeMillis(),
                new MemoryPersistence()
        );
        this.dao = dao;
    }

    public void start() throws MqttException {
        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setAutomaticReconnect(true);
        options.setCleanStart(false);
        options.setSessionExpiryInterval(MQTT_SESSION_EXPIRY_SECONDS);

        mqttClient.setCallback(new MeoMsgMqttCallback());
        mqttClient.connect(options);
        ILog.i(TAG, "start", "connected", mqttClient.getServerURI());

        mqttClient.subscribe(new MqttSubscription[]{
                new MqttSubscription(MeoTopic.UP_WILDCARD, UP_QOS)
        }, msgListener);
        ILog.i(TAG, "start", "subscribed", MeoTopic.UP_WILDCARD);
    }

    public void stop() {
        try {
            mqttClient.disconnect();
        } catch (MqttException e) {
            ILog.w(TAG, "device mqtt disconnect failed", e);
        }
    }

    public void registerMsgListener(IMqttMessageListener listener) {
        if (listener != null) {
            synchronized (msgListener) {
                IMqttMessageListener[] newListeners = Arrays.copyOf(msgListener, msgListener.length + 1);
                newListeners[newListeners.length - 1] = listener;
                System.arraycopy(newListeners, 0, msgListener, 0, newListeners.length);
            }
        }
    }

    public void sendEdgeMsg(String deviceId, String cap, int op, int value, RequestCallback<Integer> callback) {
        // validate msg frame
        if (op != MeoEdgeMsgOpcode.READ && op != MeoEdgeMsgOpcode.WRITE) {
            callback.onFailure(MeoErr.BAD_REQUEST, "op must be read or write");
            return;
        }
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            callback.onFailure(MeoErr.BAD_REQUEST, "value out of int16 range: " + value);
            return;
        }
        MeoDevice[] devices = deviceId == null ? null : dao.query(MeoDevice.class, "deviceId", deviceId);
        if (devices == null || devices.length == 0) {
            callback.onFailure(MeoErr.DEVICE_NOT_FOUND, "device not found: " + deviceId);
            return;
        }

        int idx = Arrays.asList(getDevCaps(deviceId)).indexOf(cap);
        if (idx < 0) {
            callback.onFailure(MeoErr.UNKNOWN_CAP, "device has no cap: " + cap);
            return;
        }

        // update seq for device, wrap at 31, and create pending future
        int seq = seqs.computeIfAbsent(deviceId, id -> new AtomicInteger()).getAndIncrement() & EdgeMsgDto.MAX_SEQ;
        String key = pendingKey(deviceId, seq);
        CompletableFuture<EdgeMsgDto> future = new CompletableFuture<>();
        if (pendingReplies.putIfAbsent(key, future) != null) {
            callback.onFailure(MeoErr.MSG_SEND_FAILED, "too many commands in flight for " + deviceId);
            return;
        }

        // send msg down
        try {
            MqttMessage message = new MqttMessage(new EdgeMsgDto(op, seq, idx, value).toBytes());
            message.setQos(DOWN_QOS);
            ILog.d(TAG, "sendEdgeMsg", deviceId, "type=" + op, "seq=" + seq, "idx=" + idx, "value=" + value);
            mqttClient.publish(MeoTopic.toTopicDown(deviceId), message);
        } catch (MqttException e) {
            pendingReplies.remove(key, future);
            ILog.e(TAG, "sendEdgeMsg publish failed", e);
            callback.onFailure(MeoErr.MSG_SEND_FAILED, "publish failed: " + e.getMessage());
            return;
        }

        // wait for reply with timeout
        future.orTimeout(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS).whenComplete((reply, err) -> {
            pendingReplies.remove(key, future);
            if (err != null) {
                ILog.w(TAG, "sendEdgeMsg", "no reply", deviceId, cap);
                callback.onFailure(MeoErr.MSG_TIMEOUT, "device did not reply within " + REPLY_TIMEOUT_MS + "ms");
            } else if (reply.getType() == MeoEdgeMsgOpcode.ERR) {
                ILog.w(TAG, "sendEdgeMsg", deviceId, cap, "device error=" + reply.getValue());
                callback.onFailure(reply.getValue(), "device error " + reply.getValue());
            } else {
                ILog.d(TAG, "sendEdgeMsg", deviceId, cap, "command sent");
                callback.onResult(reply.getValue(), "command sent");
            }
        });
    }

    // Cap keys in wire order (index = idx); read here so this handler needs no other handler.
    private String[] getDevCaps(String deviceId) {
        MeoDeviceCap[] rows = dao.query(MeoDeviceCap.class, "deviceId", deviceId);
        if (rows == null || rows.length == 0) {
            return new String[0];
        }
        return JsonUtil.fromJson(rows[0].getCaps(), String[].class);
    }

    private String pendingKey(String deviceId, int seq) {
        return deviceId + "#" + seq;
    }

    private class MeoMsgMqttCallback implements MqttCallback {
        @Override
        public void disconnected(MqttDisconnectResponse disconnectResponse) {
            ILog.d(TAG, "disconnected", disconnectResponse.getReturnCode());
        }

        @Override
        public void mqttErrorOccurred(MqttException exception) {

        }

        @Override
        public void deliveryComplete(IMqttToken token) {

        }

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {

        }

        @Override
        public void authPacketArrived(int reasonCode, MqttProperties properties) {

        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            String deviceId = MeoTopic.getDevIdFromTopic(topic);
            if (deviceId == null) {
                return;
            }

            EdgeMsgDto frame;
            try {
                frame = EdgeMsgDto.parse(message.getPayload());
            } catch (IllegalArgumentException e) {
                ILog.w(TAG, "up", "dropping malformed frame", topic, e.getMessage());
                return;
            }

            int type = frame.getType();
            if (type == MeoEdgeMsgOpcode.EVENT) {
                ILog.d(TAG, "event", deviceId, "idx=" + frame.getIdx(), "value=" + frame.getValue());
                return;
            }
            if (type != MeoEdgeMsgOpcode.OK && type != MeoEdgeMsgOpcode.ERR) {
                ILog.w(TAG, "up", "dropping unexpected frame type", deviceId, "type=" + type);
                return;
            }

            CompletableFuture<EdgeMsgDto> pending = pendingReplies.remove(pendingKey(deviceId, frame.getSeq()));
            if (pending == null) {
                ILog.d(TAG, "reply", "no pending request", deviceId, "seq=" + frame.getSeq());
                return;
            }
            ILog.d(TAG, "reply", deviceId, "seq=" + frame.getSeq(), "type=" + type);
            pending.complete(frame);
        }
    }
}
