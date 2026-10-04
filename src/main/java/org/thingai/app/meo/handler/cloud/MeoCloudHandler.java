package org.thingai.app.meo.handler.cloud;

import com.google.gson.JsonSyntaxException;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.thingai.app.meo.callback.MsgEventListener;
import org.thingai.app.meo.callback.RequestCallback;
import org.thingai.app.meo.define.MeoEdgeMsgOpcode;
import org.thingai.app.meo.define.MeoErr;
import org.thingai.app.meo.define.MeoTopic;
import org.thingai.app.meo.define.MeoCloudMsgOpcode;
import org.thingai.app.meo.entity.MeoDevice;
import org.thingai.app.meo.handler.mngt.MeoMngtHandler;
import org.thingai.app.meo.handler.msg.MeoMsgHandler;
import org.thingai.app.meo.handler.provision.MeoProvisionHandler;
import org.thingai.app.meo.util.JsonUtil;
import org.thingai.base.dao.Dao;
import org.thingai.base.log.ILog;

import java.nio.charset.StandardCharsets;

public class MeoCloudHandler {
    private static final String TAG = "MeoCloudHandler";
    private static final int EVENT_QOS = 0;

    private final CloudMqtt cloudMqtt = new CloudMqtt();
    private final CloudRegister cloudRegister;

    private final MeoMsgHandler msgHandler;
    private final MeoMngtHandler mngtHandler;
    private final MeoProvisionHandler provisionHandler;

    public MeoCloudHandler(
            Dao dao,
            MeoMsgHandler msgHandler,
            MeoProvisionHandler provisionHandler,
            MeoMngtHandler mngtHandler
    ) {
        this.cloudRegister = new CloudRegister(dao);
        this.msgHandler = msgHandler;
        this.provisionHandler = provisionHandler;
        this.mngtHandler = mngtHandler;

    }

    public void start() {
        cloudRegister.startCloudRegistration(new CloudRegisterCallback() {
            @Override
            public void onRegisterSuccess(String edgeId, String secret) {
                ILog.w(TAG, "onRegisterSuccess", "edgeId=" + edgeId);
                connectMqtt(edgeId, secret, new CloudMqttCallback());
                msgHandler.registerMsgListener(new CloudEventForwarder());
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

    private void connectMqtt(String edgeId, String secret, MqttCallback callback) {
        try {
            cloudMqtt.connect(edgeId, secret, callback);
        } catch (Exception e) {
            ILog.w(TAG, "link", "connect failed, cloud link off until restart", String.valueOf(e));
        }
    }

    private class CloudMqttCallback implements MqttCallback {
        @Override
        public void disconnected(MqttDisconnectResponse disconnectResponse) {

        }

        @Override
        public void mqttErrorOccurred(MqttException exception) {
            ILog.w(TAG, "link", "mqtt error", String.valueOf(exception));
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            if (!isValidCloudReqTopic(topic)) {
                ILog.w(TAG, "req", "dropping request, invalid topic=" + topic);
                return;
            }

            // No usable requestId means nowhere to reply, so a bad request is only logged.
            CloudMqttDto.Req req;
            try {
                String json = new String(message.getPayload(), StandardCharsets.UTF_8);
                req = JsonUtil.fromJson(json, CloudMqttDto.Req.class);
            } catch (JsonSyntaxException e) {
                ILog.w(TAG, "req", "dropping malformed request", e.getMessage());
                return;
            }

            if (req == null || !req.isValid()) {
                ILog.w(TAG, "req", "dropping request, bad requestId");
                return;
            }

            switch (req.op) {
                case MeoCloudMsgOpcode.DEVICE_LIST -> {
                    MeoDevice[] rows = mngtHandler.getDevices();
                    CloudMqttDto.Device[] devices = new CloudMqttDto.Device[rows.length];
                    for (int i = 0; i < rows.length; i++) {
                        CloudMqttDto.Device device = new CloudMqttDto.Device();
                        device.deviceId = rows[i].getDeviceId();
                        device.name = rows[i].getName();
                        device.description = rows[i].getDescription();
                        device.macAddress = rows[i].getMacAddress();
                        device.transportType = rows[i].getTransportType();
                        device.model = rows[i].getModel();
                        device.fwVersion = rows[i].getFwVersion();
                        device.caps = mngtHandler.getCaps(rows[i].getDeviceId());
                        devices[i] = device;
                    }
                    respondCloudMqttReq(req.requestId, new CloudMqttDto.Res(devices));
                }

                case MeoCloudMsgOpcode.DEVICE_READ, MeoCloudMsgOpcode.DEVICE_WRITE -> {
                    CloudMqttDto.DeviceArgs args;
                    try {
                        args = JsonUtil.fromJsonObject(req.args, CloudMqttDto.DeviceArgs.class);
                    } catch (JsonSyntaxException e) {
                        respondCloudMqttReq(req.requestId, new CloudMqttDto.Res(MeoErr.BAD_REQUEST, "bad args"));
                        return;
                    }

                    if (args == null || args.deviceId == null || args.cap == null) {
                        respondCloudMqttReq(req.requestId, new CloudMqttDto.Res(MeoErr.BAD_REQUEST, "deviceId and cap required"));
                        return;
                    }

                    int type;
                    if (req.op == MeoCloudMsgOpcode.DEVICE_READ) {
                        type = MeoEdgeMsgOpcode.READ;
                    } else {
                        type = MeoEdgeMsgOpcode.WRITE;
                    }

                    msgHandler.sendEdgeMsg(args.deviceId, args.cap, type, args.value, new RequestCallback<Integer>() {
                        @Override
                        public void onResult(Integer value, String message) {
                            respondCloudMqttReq(req.requestId, new CloudMqttDto.Res(new CloudMqttDto.Value(value)));
                        }

                        @Override
                        public void onFailure(int errorCode, String message) {
                            respondCloudMqttReq(req.requestId, new CloudMqttDto.Res(errorCode, message));
                        }
                    });
                }

                default -> {
                    ILog.w(TAG, "req", "dropping request, unsupported op=" + req.op);
                    respondCloudMqttReq(req.requestId, new CloudMqttDto.Res(MeoErr.BAD_REQUEST, "unsupported op"));
                }
            }

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
    }

    private class CloudEventForwarder implements MsgEventListener {
        @Override
        public void onEvent(String deviceId, String cap, int value) {
            String topic = MeoTopic.CLOUD_PREFIX + cloudRegister.edgeId() + "/event/" + deviceId;
            try {
                cloudMqtt.publish(topic, JsonUtil.toJson(new CloudMqttDto.Event(cap, value)), EVENT_QOS);
            } catch (MqttException e) {
                ILog.w(TAG, "event", "dropping event", deviceId, String.valueOf(e));
            }
        }
    }

    private void respondCloudMqttReq(String requestId, CloudMqttDto.Res res) {
        try {
            cloudMqtt.publish(MeoTopic.toTopicCloudRes(cloudRegister.edgeId(), requestId), JsonUtil.toJson(res));
        } catch (MqttException e) {
            ILog.w(TAG, "res", "dropping response", requestId, String.valueOf(e));
        }
    }

    private boolean isValidCloudReqTopic(String topic) throws IllegalArgumentException {
        return topic.equals(MeoTopic.toTopicCloudReq(cloudRegister.edgeId()));
    }
}
