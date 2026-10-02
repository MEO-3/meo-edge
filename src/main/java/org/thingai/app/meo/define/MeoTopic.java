package org.thingai.app.meo.define;

public final class MeoTopic {
    public static final String EDGE_PREFIX = "meo/v1/device/";
    public static final String UP_WILDCARD = EDGE_PREFIX + "+/up";

    private MeoTopic() {
    }

    public static String toTopicDown(String deviceId) {
        return EDGE_PREFIX + deviceId + "/down";
    }

    public static String getDevIdFromTopic(String topic) {
        if (topic == null || !topic.startsWith(EDGE_PREFIX)) {
            return null;
        }
        int end = topic.indexOf('/', EDGE_PREFIX.length());
        return end > EDGE_PREFIX.length() ? topic.substring(EDGE_PREFIX.length(), end) : null;
    }
}
