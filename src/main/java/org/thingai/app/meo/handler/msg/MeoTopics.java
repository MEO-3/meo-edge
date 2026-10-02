package org.thingai.app.meo.handler.msg;

/**
 * Device messaging topics (docs/mqtt_messaging.md): down = edge → device, up = device → edge.
 * Not BlemqttTopics, which is the internal channel to the Rust BLE service.
 */
public final class MeoTopics {
    public static final String PREFIX = "meo/v1/device/";
    public static final String UP_WILDCARD = PREFIX + "+/up";

    private MeoTopics() {
    }

    public static String down(String deviceId) {
        return PREFIX + deviceId + "/down";
    }

    /** deviceId from a device topic; null when the topic does not match. */
    public static String deviceId(String topic) {
        if (topic == null || !topic.startsWith(PREFIX)) {
            return null;
        }
        int end = topic.indexOf('/', PREFIX.length());
        return end > PREFIX.length() ? topic.substring(PREFIX.length(), end) : null;
    }
}
