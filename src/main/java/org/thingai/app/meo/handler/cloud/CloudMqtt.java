package org.thingai.app.meo.handler.cloud;

import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttReturnCode;
import org.thingai.app.meo.define.MeoTopic;
import org.thingai.base.log.ILog;

import java.nio.charset.StandardCharsets;

public class CloudMqtt {
    private static final String TAG = "CloudMqtt";

    private static final String DEFAULT_URL = "mqtts://meo.agp.io.vn:8883";
    private static final String URL = url(System.getenv("MEO_CLOUD_MQTT_URL"));
    private static final int CONNECT_TIMEOUT_S = 10;
    private static final int QOS = 1;

    private volatile MqttClient client;
    private String statusTopic;

    public void connect(String edgeId, String secret, MqttCallback callback) throws MqttException {
        String clientId = "edge:" + edgeId;
        String statusTopic = MeoTopic.toTopicCloudStatus(edgeId);
        MqttClient client = new MqttClient(toPahoUri(URL), clientId, new MemoryPersistence());
        try {
            client.setCallback(callback);

            MqttConnectionOptions options = new MqttConnectionOptions();
            options.setUserName(clientId);
            options.setPassword(secret.getBytes(StandardCharsets.UTF_8));
            options.setCleanStart(true);
            options.setAutomaticReconnect(false);
            options.setConnectionTimeout(CONNECT_TIMEOUT_S);
            options.setWill(statusTopic, statusMessage("offline"));
            client.connect(options);

            client.publish(statusTopic, statusMessage("online"));
            client.subscribe(MeoTopic.toTopicCloudReq(edgeId), QOS);
        } catch (MqttException | RuntimeException e) {
            close(client);
            throw e;
        }
        this.statusTopic = statusTopic;
        this.client = client;
        ILog.i(TAG, "link", "connected", "edgeId=" + edgeId);
    }

    public boolean isOpen() {
        return client != null;
    }

    // Drops the current client without saying offline; for a link that is already lost.
    public void close() {
        MqttClient client = this.client;
        this.client = null;
        close(client);
    }

    // Clean shutdown. A clean disconnect does not fire the LWT, so say offline ourselves.
    public void disconnect() {
        MqttClient client = this.client;
        this.client = null;
        if (client == null) {
            return;
        }
        try {
            client.publish(statusTopic, statusMessage("offline"));
        } catch (Exception e) {
            ILog.w(TAG, "disconnect", "offline publish failed", e.getMessage());
        }
        close(client);
    }

    // The broker also denies when the cloud API is down, so this is not proof the edge was deleted.
    public static boolean isAuthRejected(Exception e) {
        int reason = e instanceof MqttException ? ((MqttException) e).getReasonCode() : -1;
        return reason == MqttReturnCode.RETURN_CODE_BAD_USERNAME_OR_PASSWORD
                || reason == MqttReturnCode.RETURN_CODE_NOT_AUTHORIZED;
    }

    private static void close(MqttClient client) {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (Exception e) {
            ILog.d(TAG, "link", "close failed", e.getMessage());
        }
    }

    private static MqttMessage statusMessage(String status) {
        MqttMessage message = new MqttMessage(status.getBytes(StandardCharsets.UTF_8));
        message.setQos(QOS);
        message.setRetained(true);
        return message;
    }

    static String url(String raw) {
        String url = raw == null ? "" : raw.trim();
        return url.isEmpty() ? DEFAULT_URL : url;
    }

    // Paho only knows tcp:// and ssl://; config uses the usual mqtt:// (local dev) and mqtts://.
    static String toPahoUri(String url) {
        if (url.startsWith("mqtts://")) {
            return "ssl://" + url.substring("mqtts://".length());
        }
        if (url.startsWith("mqtt://")) {
            return "tcp://" + url.substring("mqtt://".length());
        }
        return url;
    }
}
