package org.thingai.app.meo;

import io.javalin.Javalin;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import org.thingai.app.meo.api.Route;

public class Main {
    private static final String TAG = "Main";

    public static void main(String[] args) {
        MeoService meoService = new MeoService();
        meoService.init();

        Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> cors.addRule(it -> it.anyHost()));
            config.registerPlugin(new OpenApiPlugin(openApiConfig -> openApiConfig
                    .withDocumentationPath("/openapi.json")
                    .withDefinitionConfiguration((version, definition) -> definition
                            .info(info -> info.title("MEO Edge API").version("v1")))));
            config.registerPlugin(new SwaggerPlugin(ui -> ui.withDocumentationPath("/openapi.json")));
            new Route(config, meoService.deviceHandler(), meoService.provisionHandler(),
                    meoService.controlHandler()).addRoutes();
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
