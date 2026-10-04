package org.thingai.app.meo.handler.msg;

import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.thingai.app.meo.callback.RequestCallback;
import org.thingai.app.meo.define.MeoErr;
import org.thingai.app.meo.define.MeoTopic;
import org.thingai.app.meo.handler.mngt.MeoMngtHandler;
import org.thingai.base.log.ILog;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MeoMsgHandler implements IMqttMessageListener {
    private static final String TAG = "MeoMsgHandler";

    private static final long REPLY_TIMEOUT_MS = 10_000;
    private static final int DOWN_QOS = 1;
    private static final int UP_QOS = 0;
    private static final long MQTT_SESSION_EXPIRY_SECONDS = 300;

    private final MqttClient mqttClient;
    private final MeoMngtHandler deviceHandler;

    // Keyed by deviceId+seq so a stray reply can't complete another device's request.
    private final Map<String, CompletableFuture<EdgeMsgDto>> pendingReplies = new ConcurrentHashMap<>();
    // seqs per device, max 31
    private final Map<String, AtomicInteger> seqs = new ConcurrentHashMap<>();

    // Own connection to the broker — a separate protocol from blemqtt.
    public MeoMsgHandler(MeoMngtHandler deviceHandler, String brokerUrl) throws MqttException {
        this.mqttClient = new MqttClient(brokerUrl, "meo-" + System.currentTimeMillis(), new MemoryPersistence());
        this.deviceHandler = deviceHandler;
    }

    public void start() throws MqttException {
        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setAutomaticReconnect(true);
        options.setCleanStart(false); // disable this so topics don't have to re-subscribe.
        options.setSessionExpiryInterval(MQTT_SESSION_EXPIRY_SECONDS);
        mqttClient.connect(options);
        ILog.i(TAG, "start", "connected", mqttClient.getServerURI());

        mqttClient.subscribe(
                new MqttSubscription[]{new MqttSubscription(MeoTopic.UP_WILDCARD, UP_QOS)},
                new IMqttMessageListener[]{this::messageArrived});
        ILog.i(TAG, "start", "subscribed", MeoTopic.UP_WILDCARD);
    }

    public void stop() {
        try {
            mqttClient.disconnect();
        } catch (MqttException e) {
            ILog.w(TAG, "device mqtt disconnect failed", e);
        }
    }

    public void sendDown(String deviceId, String cap, int op, int value, RequestCallback<Integer> callback) {
        // validate msg frame
        if (op != EdgeMsgDto.TYPE_READ && op != EdgeMsgDto.TYPE_WRITE) {
            callback.onFailure(MeoErr.BAD_REQUEST, "op must be read or write");
            return;
        }
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            callback.onFailure(MeoErr.BAD_REQUEST, "value out of int16 range: " + value);
            return;
        }
        if (deviceHandler.getDevice(deviceId) == null) {
            callback.onFailure(MeoErr.DEVICE_NOT_FOUND, "device not found: " + deviceId);
            return;
        }

        int idx = Arrays.asList(deviceHandler.getCaps(deviceId)).indexOf(cap);
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
            ILog.d(TAG, "send", deviceId, "type=" + op, "seq=" + seq, "idx=" + idx, "value=" + value);
            mqttClient.publish(MeoTopic.toTopicDown(deviceId), message);
        } catch (MqttException e) {
            pendingReplies.remove(key, future);
            ILog.e(TAG, "sendDown publish failed", e);
            callback.onFailure(MeoErr.MSG_SEND_FAILED, "publish failed: " + e.getMessage());
            return;
        }

        // wait for reply with timeout
        future.orTimeout(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS).whenComplete((reply, err) -> {
            pendingReplies.remove(key, future);
            if (err != null) {
                ILog.w(TAG, "sendDown", "no reply", deviceId, cap);
                callback.onFailure(MeoErr.MSG_TIMEOUT, "device did not reply within " + REPLY_TIMEOUT_MS + "ms");
            } else if (reply.getType() == EdgeMsgDto.TYPE_ERR) {
                ILog.w(TAG, "sendDown", deviceId, cap, "device error=" + reply.getValue());
                callback.onFailure(reply.getValue(), "device error " + reply.getValue());
            } else {
                ILog.d(TAG, "sendDown", deviceId, cap, "command sent");
                callback.onResult(reply.getValue(), "command sent");
            }
        });
    }

    private String pendingKey(String deviceId, int seq) {
        return deviceId + "#" + seq;
    }

    // mqtt up listener
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
        if (type == EdgeMsgDto.TYPE_EVENT) {
            ILog.d(TAG, "event", deviceId, "idx=" + frame.getIdx(), "value=" + frame.getValue());
            return;
        }
        if (type != EdgeMsgDto.TYPE_OK && type != EdgeMsgDto.TYPE_ERR) {
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
