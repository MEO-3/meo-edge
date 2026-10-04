package org.thingai.app.meo.handler.cloud;

import org.thingai.app.meo.handler.cloud.CloudApiDto.RegisterReq;
import org.thingai.app.meo.handler.cloud.CloudApiDto.RegisterRes;
import org.thingai.app.meo.handler.cloud.CloudApiDto.StatusReq;
import org.thingai.app.meo.handler.cloud.CloudApiDto.StatusRes;
import org.thingai.app.meo.util.JsonUtil;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;


public final class CloudApi {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String DEFAULT_API_URL = "https://api-meo.agp.io.vn";
    private static final String API_URL = apiUrl(System.getenv("MEO_CLOUD_API_URL"));
    private static final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    private CloudApi() {
    }

    public static final class Reply<T> {
        public final int status;
        public final T body;

        Reply(int status, T body) {
            this.status = status;
            this.body = body;
        }

        public boolean ok() {
            return status / 100 == 2;
        }
    }

    public static Reply<RegisterRes> register(String mac, String claimCode)
            throws IOException, InterruptedException {
        return post(API_URL + "/edges/register", new RegisterReq(mac, claimCode), RegisterRes.class);
    }

    public static Reply<StatusRes> status(String mac, String secret)
            throws IOException, InterruptedException {
        return post(API_URL + "/edges/status", new StatusReq(mac, secret), StatusRes.class);
    }

    static String apiUrl(String raw) {
        String url = raw == null ? "" : raw.trim().replaceAll("/+$", "");
        return url.isEmpty() ? DEFAULT_API_URL : url;
    }

    private static <T> Reply<T> post(String url, Object body, Class<T> resType)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        try {
            return new Reply<>(status, status / 100 == 2 ? JsonUtil.fromJson(response.body(), resType) : null);
        } catch (RuntimeException e) {
            throw new IOException("unreadable reply from " + url + ", status=" + status);
        }
    }
}
