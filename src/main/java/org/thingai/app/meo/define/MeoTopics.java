package org.thingai.app.meo.define;

/**
 * Device messaging topics (docs/mqtt_messaging.md): down = edge → device, up = device → edge.
 * Not BlemqttTopics, which is the internal channel to the Rust BLE service.
 */
public final class MeoTopics {
    public static final String EDGE_PREFIX = "meo/v1/device/";
    public static final String UP_WILDCARD = EDGE_PREFIX + "+/up";

    private MeoTopics() {
    }

    public static String toTopicDown(String deviceId) {
        return EDGE_PREFIX + deviceId + "/down";
    }

    /** deviceId from a device topic; null when the topic does not match. */
    public static String getDevIdFromTopic(String topic) {
        if (topic == null || !topic.startsWith(EDGE_PREFIX)) {
            return null;
        }
        int end = topic.indexOf('/', EDGE_PREFIX.length());
        return end > EDGE_PREFIX.length() ? topic.substring(EDGE_PREFIX.length(), end) : null;
    }
}
