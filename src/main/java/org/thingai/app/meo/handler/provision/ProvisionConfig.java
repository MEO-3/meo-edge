package org.thingai.app.meo.handler.provision;

import org.thingai.app.meo.util.NetUtil;

final class ProvisionConfig {
    private ProvisionConfig() {
    }

    public static final int DEFAULT_BROKER_PORT = 1883;
    public static final String DEFAULT_BROKER_HOST = NetUtil.lanIpv4();
}
