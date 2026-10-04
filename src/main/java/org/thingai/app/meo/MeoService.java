package org.thingai.app.meo;


import org.thingai.app.meo.util.DaoKvUtil;
import org.thingai.app.meo.entity.MeoDevice;
import org.thingai.app.meo.entity.MeoDeviceCap;
import org.thingai.app.meo.handler.cloud.MeoCloudHandler;
import org.thingai.app.meo.handler.msg.MeoMsgHandler;
import org.thingai.app.meo.handler.mngt.MeoMngtHandler;
import org.thingai.app.meo.handler.provision.MeoProvisionHandler;
import org.thingai.base.Service;
import org.thingai.base.dao.Dao;
import org.thingai.base.log.ILog;
import org.thingai.platform.dao.DaoSqlite;

import java.io.File;

public class MeoService extends Service {
    private static final String TAG = "MeoService";

    private static final String DEFAULT_LOCAL_MQTT_BROKER = "tcp://localhost:1883";

    private Dao dao;
    private MeoMngtHandler mngtHandler;
    private MeoProvisionHandler provisionHandler;
    private MeoMsgHandler msgHandler;
    private MeoCloudHandler cloudHandler;

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
                MeoDeviceCap.class,
                DaoKvUtil.class
        });
        mngtHandler = new MeoMngtHandler(dao);

        String broker = System.getenv("MEO_MQTT_BROKER");
        String brokerUrl = broker != null && !broker.trim().isEmpty() ? broker : DEFAULT_LOCAL_MQTT_BROKER;

        try {
            provisionHandler = new MeoProvisionHandler(brokerUrl, dao);
            provisionHandler.start();
        } catch (Exception e) {
            ILog.e(TAG, "provision mqtt connect failed", e);
            throw new RuntimeException("provision mqtt connect failed", e);
        }

        try {
            msgHandler = new MeoMsgHandler(brokerUrl, dao);
            msgHandler.start();
        } catch (Exception e) {
            ILog.e(TAG, "device mqtt connect failed", e);
            throw new RuntimeException("device mqtt connect failed", e);
        }

        // no exception here; edge can work without cloud
        // di: msgHandler, provisionHandler, mngtHandler
        cloudHandler = new MeoCloudHandler(dao, msgHandler, provisionHandler, mngtHandler);
        cloudHandler.start();
    }

    @Override
    protected void onServiceShutdown() {
        if (cloudHandler != null) {
            cloudHandler.stop();
        }
        if (provisionHandler != null) {
            provisionHandler.stop();
        }
        if (msgHandler != null) {
            msgHandler.stop();
        }
        if (dao instanceof DaoSqlite) {
            ((DaoSqlite) dao).close();
        }
    }

    public MeoMngtHandler mngtHandler() {
        return mngtHandler;
    }

    public MeoProvisionHandler provisionHandler() {
        return provisionHandler;
    }

    public MeoMsgHandler msgHandler() {
        return msgHandler;
    }
}
