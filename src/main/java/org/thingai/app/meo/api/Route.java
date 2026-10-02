package org.thingai.app.meo.api;

import io.javalin.config.JavalinConfig;
import org.thingai.app.meo.api.route.ControlRoute;
import org.thingai.app.meo.api.route.DeviceRoute;
import org.thingai.app.meo.api.route.ProvisionRoute;
import org.thingai.app.meo.handler.msg.MeoMsgHandler;
import org.thingai.app.meo.handler.mngt.MeoMngtHandler;
import org.thingai.app.meo.handler.provision.MeoProvisionHandler;

// Registers all HTTP routes. Endpoint logic lives in api/route classes;
// this class only wires handlers to them.
public class Route {
    private final JavalinConfig config;
    private final MeoMngtHandler deviceHandler;
    private final MeoProvisionHandler provisionHandler;
    private final MeoMsgHandler msgHandler;

    public Route(JavalinConfig config, MeoMngtHandler deviceHandler,
                 MeoProvisionHandler provisionHandler, MeoMsgHandler msgHandler) {
        this.config = config;
        this.deviceHandler = deviceHandler;
        this.provisionHandler = provisionHandler;
        this.msgHandler = msgHandler;
    }

    public void addRoutes() {
        config.routes.get("/", ctx -> ctx.json("meow"));

        new DeviceRoute(deviceHandler).addRoutes(config);
        new ProvisionRoute(provisionHandler).addRoutes(config);
        new ControlRoute(msgHandler).addRoutes(config);
    }
}
