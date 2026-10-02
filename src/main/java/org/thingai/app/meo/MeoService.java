package org.thingai.app.meo;


import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.thingai.app.meo.blemqtt.BlemqttClient;
import org.thingai.app.meo.blemqtt.BlemqttConfig;
import org.thingai.app.meo.entity.MeoDevice;
import org.thingai.app.meo.entity.MeoDeviceCap;
import org.thingai.app.meo.handler.control.MeoControlHandler;
import org.thingai.app.meo.handler.mngt.MeoMngtHandler;
import org.thingai.app.meo.handler.provision.MeoProvisionHandler;
import org.thingai.base.Service;
import org.thingai.base.dao.Dao;
import org.thingai.base.log.ILog;
import org.thingai.platform.dao.DaoSqlite;

import java.io.File;

public class MeoService extends Service {
    private static final String TAG = "MeoService";

    // Just needs to outlive a reconnect (Paho backs off to 128s); client id is per-run.
    private static final long MQTT_SESSION_EXPIRY_SECONDS = 300;

    private Dao dao;
    private BlemqttClient blemqttClient;
    private MqttClient deviceMqttClient;
    private MeoMngtHandler deviceHandler;
    private MeoProvisionHandler provisionHandler;
    private MeoControlHandler controlHandler;

    protected MeoService() {
        super("MeoService");
        setAppDirName("meo_service");
        setVersion("0.1");

        ILog.ENABLE_LOGGING = true;
        ILog.LOG_LEVEL = ILog.DEBUG;
    }

    @Override
    protected void onServiceInit() {
        String dataDir = System.getenv("MEO_DATA_DIR");
        String appDir = dataDir != null && !dataDir.trim().isEmpty() ? dataDir : getAppDir();
        new File(appDir).mkdirs();

        dao = new DaoSqlite(appDir + "/meo.db");
        dao.initDao(new Class[]{
                MeoDevice.class,
                MeoDeviceCap.class
        });
        deviceHandler = new MeoMngtHandler(dao);

        BlemqttConfig blemqttConfig = new BlemqttConfig();
        String broker = System.getenv("MEO_MQTT_BROKER");
        if (broker != null && !broker.trim().isEmpty()) {
            blemqttConfig.setBrokerUrl(broker);
        }
        blemqttClient = new BlemqttClient(blemqttConfig);
        try {
            blemqttClient.connect();
            ILog.d(TAG, "blemqtt connect");
        } catch (Exception e) {
            ILog.e(TAG, "blemqtt connect failed", e);
        }
        // TODO: Error handling blemqtt connect failed here
        provisionHandler = new MeoProvisionHandler(blemqttClient, dao);

        // Own connection to the same broker — a separate protocol from blemqtt.
        try {
            deviceMqttClient = new MqttClient(blemqttConfig.getBrokerUrl(),
                    "meo-" + System.currentTimeMillis(), new MemoryPersistence());
            MqttConnectionOptions options = new MqttConnectionOptions();
            options.setAutomaticReconnect(true);
            options.setCleanStart(false); // disable this so topics don't have to re-subscribe.
            options.setSessionExpiryInterval(MQTT_SESSION_EXPIRY_SECONDS);
            deviceMqttClient.connect(options);

            controlHandler = new MeoControlHandler(deviceMqttClient, deviceHandler);
            controlHandler.start();
            ILog.i(TAG, "device mqtt connected", blemqttConfig.getBrokerUrl());
        } catch (Exception e) {
            ILog.e(TAG, "device mqtt connect failed", e);
        }
    }

    @Override
    protected void onServiceShutdown() {
        if (blemqttClient != null) {
            try {
                blemqttClient.disconnect();
            } catch (Exception e) {
                ILog.w(TAG, "blemqtt disconnect failed", e);
            }
        }
        if (deviceMqttClient != null) {
            try {
                deviceMqttClient.disconnect();
            } catch (Exception e) {
                ILog.w(TAG, "device mqtt disconnect failed", e);
            }
        }
        if (dao instanceof DaoSqlite) {
            ((DaoSqlite) dao).close();
        }
    }

    public MeoMngtHandler deviceHandler() {
        return deviceHandler;
    }

    public MeoProvisionHandler provisionHandler() {
        return provisionHandler;
    }

    public MeoControlHandler controlHandler() {
        return controlHandler;
    }
}
