package org.thingai.app.meo.handler.cloud;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.thingai.base.dao.Dao;
import org.thingai.base.log.ILog;


public class MeoCloudHandler {
    private static final String TAG = "MeoCloudHandler";

    private final Dao dao;
    private final CloudMqtt cloudMqtt = new CloudMqtt();

    private CloudRegister cloudRegister;

    public MeoCloudHandler(Dao dao) {
        this.dao = dao;
    }

    // Nothing here retries: a failed registration or connect leaves the cloud link off until the edge restarts.
    public void start() {
        cloudRegister = new CloudRegister(dao);
        cloudRegister.startCloudRegistration(new CloudRegisterCallback() {
            @Override
            public void onRegisterSuccess(String edgeId, String secret) {
                ILog.w(TAG, "onRegisterSuccess", "edgeId=" + edgeId);
                connectMqtt(edgeId, secret);
            }

            @Override
            public void onRegisterFailed(int errorCode, String errorMessage) {
                ILog.w(TAG, "start", "cloud link off until restart", "err=" + errorCode, errorMessage);
            }
        });
    }

    public void stop() {
        cloudRegister.stop();
        cloudMqtt.disconnect();
    }

    private void connectMqtt(String edgeId, String secret) {
        try {
            cloudMqtt.connect(edgeId, secret, new MqttCallback() {
                @Override
                public void disconnected(MqttDisconnectResponse disconnectResponse) {

                }

                @Override
                public void mqttErrorOccurred(MqttException exception) {

                }

                @Override
                public void messageArrived(String topic, MqttMessage message) throws Exception {

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
            });
        } catch (Exception e) {
            ILog.w(TAG, "link", "connect failed, cloud link off until restart", String.valueOf(e));
        }
    }
}
