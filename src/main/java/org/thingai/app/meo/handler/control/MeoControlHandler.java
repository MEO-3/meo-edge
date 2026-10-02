package org.thingai.app.meo.handler.control;

import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.thingai.app.meo.callback.RequestCallback;
import org.thingai.app.meo.define.ErrorCode;
import org.thingai.app.meo.define.MeoCmdErrCode;
import org.thingai.app.meo.handler.mngt.MeoMngtHandler;
import org.thingai.app.meo.handler.msg.MeoFrame;
import org.thingai.app.meo.handler.msg.MeoTopics;
import org.thingai.base.log.ILog;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

// Device control over MQTT (docs/mqtt_messaging.md): publish READ/WRITE on down, block for OK/ERR on up.
public class MeoControlHandler {
    private static final String TAG = "MeoControlHandler";

    private static final long REPLY_TIMEOUT_MS = 10_000;
    private static final int DOWN_QOS = 1;
    private static final int UP_QOS = 0;

    private final MqttClient mqttClient;
    private final MeoMngtHandler deviceHandler;

    // Keyed by deviceId+seq so a stray reply can't complete another device's request.
    private final Map<String, CompletableFuture<MeoFrame>> pendingReplies = new ConcurrentHashMap<>();

    // Per-device rotating seq 0..31; per device so traffic to others can't wrap it early.
    private final Map<String, AtomicInteger> seqs = new ConcurrentHashMap<>();

    public MeoControlHandler(MqttClient mqttClient, MeoMngtHandler deviceHandler) {
        this.mqttClient = mqttClient;
        this.deviceHandler = deviceHandler;
    }

    // Subscribes once; the persistent session survives reconnects. Must use the
    // MqttSubscription[] overload — Paho 1.2.5's String/String[] listener forms recurse and blow the stack.
    public void start() throws MqttException {
        mqttClient.subscribe(
                new MqttSubscription[]{new MqttSubscription(MeoTopics.UP_WILDCARD, UP_QOS)},
                new IMqttMessageListener[]{this::onUp});
        ILog.i(TAG, "start", "subscribed", MeoTopics.UP_WILDCARD);
    }

    // Sends a read or write (MeoFrame.TYPE_READ/TYPE_WRITE) to a device cap by key; waits for the reply's value.
    public void sendCommand(String deviceId, String cap, int op, int value, RequestCallback<Integer> callback) {
        if (isEmpty(deviceId) || isEmpty(cap)) {
            callback.onFailure(ErrorCode.CONTROL_FAILED, "device id and cap are required");
            return;
        }
        if (op != MeoFrame.TYPE_READ && op != MeoFrame.TYPE_WRITE) {
            callback.onFailure(ErrorCode.CONTROL_FAILED, "op must be read or write");
            return;
        }
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            callback.onFailure(ErrorCode.CONTROL_FAILED, "value out of int16 range: " + value);
            return;
        }
        if (deviceHandler.getDevice(deviceId) == null) {
            callback.onFailure(ErrorCode.DEVICE_NOT_FOUND, "device not found: " + deviceId);
            return;
        }
        // getCaps is in idx order with no gaps, so the array position is the wire idx.
        int idx = Arrays.asList(deviceHandler.getCaps(deviceId)).indexOf(cap);
        if (idx < 0) {
            callback.onFailure(ErrorCode.CONTROL_CAP_NOT_SUPPORTED, "device has no cap: " + cap);
            return;
        }
        try {
            MeoFrame reply = sendBlocking(deviceId, idx, op, value);
            if (reply.getType() == MeoFrame.TYPE_ERR) {
                ILog.w(TAG, "sendCommand", deviceId, cap, "device error=" + reply.getValue());
                callback.onFailure(deviceErrorCode(reply.getValue()), deviceErrorMessage(reply.getValue()));
                return;
            }
            callback.onResult(reply.getValue(), "command sent");
        } catch (TimeoutException e) {
            ILog.w(TAG, "sendCommand", "no reply", deviceId, cap);
            callback.onFailure(ErrorCode.CONTROL_TIMEOUT,
                    "device did not reply within " + REPLY_TIMEOUT_MS + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callback.onFailure(ErrorCode.CONTROL_FAILED, "interrupted while waiting for reply");
        } catch (Exception e) {
            ILog.e(TAG, "sendCommand failed", e);
            callback.onFailure(ErrorCode.CONTROL_FAILED, failureMessage(e, "command failed"));
        }
    }

    // Publishes and blocks for the reply; finally prevents a timed-out request from leaking its entry.
    private MeoFrame sendBlocking(String deviceId, int idx, int op, int value) throws Exception {
        int seq = seqs.computeIfAbsent(deviceId, id -> new AtomicInteger()).getAndIncrement() & MeoFrame.MAX_SEQ;
        String key = pendingKey(deviceId, seq);
        CompletableFuture<MeoFrame> future = new CompletableFuture<>();
        // ponytail: 5-bit seq caps a device at 32 in-flight commands; widen the seq bits if that's ever hit.
        if (pendingReplies.putIfAbsent(key, future) != null) {
            throw new IllegalStateException("too many commands in flight for " + deviceId);
        }
        try {
            MqttMessage message = new MqttMessage(new MeoFrame(op, seq, idx, value).toBytes());
            message.setQos(DOWN_QOS);
            ILog.d(TAG, "send", deviceId, "type=" + op, "seq=" + seq, "idx=" + idx, "value=" + value);
            mqttClient.publish(MeoTopics.down(deviceId), message);
            return future.get(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } finally {
            pendingReplies.remove(key, future);
        }
    }

    // Up subscription callback; runs on the MQTT thread. OK/ERR complete a pending request,
    // EVENT is only logged until the client-side event topic exists.
    private void onUp(String topic, MqttMessage message) {
        String deviceId = MeoTopics.deviceId(topic);
        if (deviceId == null) {
            return;
        }

        MeoFrame frame;
        try {
            frame = MeoFrame.parse(message.getPayload());
        } catch (IllegalArgumentException e) {
            ILog.w(TAG, "up", "dropping malformed frame", topic, e.getMessage());
            return;
        }

        int type = frame.getType();
        if (type == MeoFrame.TYPE_EVENT) {
            ILog.d(TAG, "event", deviceId, "idx=" + frame.getIdx(), "value=" + frame.getValue());
            return;
        }
        if (type != MeoFrame.TYPE_OK && type != MeoFrame.TYPE_ERR) {
            ILog.w(TAG, "up", "dropping unexpected frame type", deviceId, "type=" + type);
            return;
        }

        CompletableFuture<MeoFrame> pending = pendingReplies.remove(pendingKey(deviceId, frame.getSeq()));
        if (pending == null) {
            // Already timed out, or a duplicate.
            ILog.d(TAG, "reply", "no pending request", deviceId, "seq=" + frame.getSeq());
            return;
        }
        ILog.d(TAG, "reply", deviceId, "seq=" + frame.getSeq(), "type=" + type);
        pending.complete(frame);
    }

    private String pendingKey(String deviceId, int seq) {
        return deviceId + "#" + seq;
    }

    // The device is the authority on which caps and ops it implements.
    private int deviceErrorCode(int error) {
        if (error == MeoCmdErrCode.ERR_UNKNOWN_CAP) {
            return ErrorCode.CONTROL_CAP_NOT_SUPPORTED;
        }
        if (error == MeoCmdErrCode.ERR_OP_NOT_SUPPORTED) {
            return ErrorCode.CONTROL_OP_NOT_SUPPORTED;
        }
        return ErrorCode.CONTROL_DEVICE_ERROR;
    }

    private String deviceErrorMessage(int error) {
        if (error == MeoCmdErrCode.ERR_BAD_REQUEST) {
            return "device rejected the command as malformed";
        }
        if (error == MeoCmdErrCode.ERR_UNKNOWN_CAP) {
            return "device does not implement the requested capability";
        }
        if (error == MeoCmdErrCode.ERR_HANDLE_FAILED) {
            return "device failed to execute the command";
        }
        if (error == MeoCmdErrCode.ERR_OP_NOT_SUPPORTED) {
            return "device cap does not support this op";
        }
        return "device reported error " + error;
    }

    private String failureMessage(Throwable t, String fallback) {
        return t.getMessage() != null ? t.getMessage() : fallback;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
