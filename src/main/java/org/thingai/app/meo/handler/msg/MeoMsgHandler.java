package org.thingai.app.meo.handler.msg;

import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.thingai.app.meo.callback.RequestCallback;
import org.thingai.app.meo.define.MeoErr;
import org.thingai.app.meo.define.MeoMsgErr;
import org.thingai.app.meo.define.MeoMsgFrame;
import org.thingai.app.meo.define.MeoTopics;
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

    private final MqttClient mqttClient;
    private final MeoMngtHandler deviceHandler;

    // Keyed by deviceId+seq so a stray reply can't complete another device's request.
    private final Map<String, CompletableFuture<MeoMsgFrame>> pendingReplies = new ConcurrentHashMap<>();
    // seqs per device, max 31
    private final Map<String, AtomicInteger> seqs = new ConcurrentHashMap<>();

    public MeoMsgHandler(MqttClient mqttClient, MeoMngtHandler deviceHandler) {
        this.mqttClient = mqttClient;
        this.deviceHandler = deviceHandler;
    }

    public void start() throws MqttException {
        mqttClient.subscribe(
                new MqttSubscription[]{new MqttSubscription(MeoTopics.UP_WILDCARD, UP_QOS)},
                new IMqttMessageListener[]{this::messageArrived});
        ILog.i(TAG, "start", "subscribed", MeoTopics.UP_WILDCARD);
    }

    public void sendDown(String deviceId, String cap, int op, int value, RequestCallback<Integer> callback) {
        // validate msg frame
        if (op != MeoMsgFrame.TYPE_READ && op != MeoMsgFrame.TYPE_WRITE) {
            callback.onFailure(MeoMsgErr.BAD_REQUEST, "op must be read or write");
            return;
        }
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            callback.onFailure(MeoMsgErr.BAD_REQUEST, "value out of int16 range: " + value);
            return;
        }
        if (deviceHandler.getDevice(deviceId) == null) {
            callback.onFailure(MeoErr.DEVICE_NOT_FOUND, "device not found: " + deviceId);
            return;
        }

        int idx = Arrays.asList(deviceHandler.getCaps(deviceId)).indexOf(cap);
        if (idx < 0) {
            callback.onFailure(MeoMsgErr.UNKNOWN_CAP, "device has no cap: " + cap);
            return;
        }

        // update seq for device, wrap at 31, and create pending future
        int seq = seqs.computeIfAbsent(deviceId, id -> new AtomicInteger()).getAndIncrement() & MeoMsgFrame.MAX_SEQ;
        String key = pendingKey(deviceId, seq);
        CompletableFuture<MeoMsgFrame> future = new CompletableFuture<>();
        if (pendingReplies.putIfAbsent(key, future) != null) {
            callback.onFailure(MeoMsgErr.SEND_FAILED, "too many commands in flight for " + deviceId);
            return;
        }

        // send msg down
        try {
            MqttMessage message = new MqttMessage(new MeoMsgFrame(op, seq, idx, value).toBytes());
            message.setQos(DOWN_QOS);
            ILog.d(TAG, "send", deviceId, "type=" + op, "seq=" + seq, "idx=" + idx, "value=" + value);
            mqttClient.publish(MeoTopics.toTopicDown(deviceId), message);
        } catch (MqttException e) {
            pendingReplies.remove(key, future);
            ILog.e(TAG, "sendDown publish failed", e);
            callback.onFailure(MeoMsgErr.SEND_FAILED, "publish failed: " + e.getMessage());
            return;
        }

        // wait for reply with timeout
        future.orTimeout(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS).whenComplete((reply, err) -> {
            pendingReplies.remove(key, future);
            if (err != null) {
                ILog.w(TAG, "sendDown", "no reply", deviceId, cap);
                callback.onFailure(MeoMsgErr.TIMEOUT, "device did not reply within " + REPLY_TIMEOUT_MS + "ms");
            } else if (reply.getType() == MeoMsgFrame.TYPE_ERR) {
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
        String deviceId = MeoTopics.getDevIdFromTopic(topic);
        if (deviceId == null) {
            return;
        }

        MeoMsgFrame frame;
        try {
            frame = MeoMsgFrame.parse(message.getPayload());
        } catch (IllegalArgumentException e) {
            ILog.w(TAG, "up", "dropping malformed frame", topic, e.getMessage());
            return;
        }

        int type = frame.getType();
        if (type == MeoMsgFrame.TYPE_EVENT) {
            ILog.d(TAG, "event", deviceId, "idx=" + frame.getIdx(), "value=" + frame.getValue());
            return;
        }
        if (type != MeoMsgFrame.TYPE_OK && type != MeoMsgFrame.TYPE_ERR) {
            ILog.w(TAG, "up", "dropping unexpected frame type", deviceId, "type=" + type);
            return;
        }

        CompletableFuture<MeoMsgFrame> pending = pendingReplies.remove(pendingKey(deviceId, frame.getSeq()));
        if (pending == null) {
            ILog.d(TAG, "reply", "no pending request", deviceId, "seq=" + frame.getSeq());
            return;
        }
        ILog.d(TAG, "reply", deviceId, "seq=" + frame.getSeq(), "type=" + type);
        pending.complete(frame);
    }
}
