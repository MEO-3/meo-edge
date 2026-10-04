package org.thingai.app.meo;

import io.javalin.Javalin;
import io.javalin.json.JavalinGson;
import org.thingai.app.meo.localapi.Route;

public class Main {
    private static final String TAG = "Main";

    public static void main(String[] args) {
        MeoService meoService = new MeoService();
        meoService.init();

        Javalin.create(config -> {
            config.jsonMapper(new JavalinGson());
            config.bundledPlugins.enableCors(cors -> cors.addRule(it -> it.anyHost()));
            new Route(config, meoService.deviceHandler(), meoService.provisionHandler(),
                    meoService.msgHandler()).addRoutes();
        }).start(getPort());
    }

    private static int getPort() {
        String port = System.getenv("MEO_SERVICE_PORT");
        if (port == null || port.trim().isEmpty()) {
            return 7070;
        }
        return Integer.parseInt(port);
    }
}
