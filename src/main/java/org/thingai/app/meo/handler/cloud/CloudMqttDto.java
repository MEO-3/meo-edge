package org.thingai.app.meo.handler.cloud;

import com.google.gson.JsonObject;

import java.util.regex.Pattern;

public final class CloudMqttDto {
    private CloudMqttDto() {
    }

    // req
    public static final class Req {
        private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

        public String requestId;
        public int op;
        public JsonObject args;

        // requestId becomes a topic segment of the reply, so it must not carry '/', '+' or '#'.
        public boolean isValid() {
            return requestId != null && REQUEST_ID.matcher(requestId).matches();
        }
    }

    // args of DEVICE_READ / DEVICE_WRITE; value is only used by writes
    public static final class DeviceArgs {
        public String deviceId;
        public String cap;
        public int value;
    }

    // res/{requestId}; a null data or error is left out of the JSON
    public static final class Res {
        public final boolean ok;
        public final Object data;
        public final Err error;

        public Res(Object data) {
            this.ok = true;
            this.data = data;
            this.error = null;
        }

        public Res(int code, String message) {
            this.ok = false;
            this.data = null;
            this.error = new Err(code, message);
        }
    }

    public static final class Err {
        public final int code;
        public final String message;

        public Err(int code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    // data of DEVICE_LIST, one per device
    public static final class Device {
        public String deviceId;
        public String name;
        public String description;
        public String macAddress;
        public int transportType;
        public String model;
        public String fwVersion;
        public String[] caps;
    }

    // data of DEVICE_READ / DEVICE_WRITE
    public static final class Value {
        public final int value;

        public Value(int value) {
            this.value = value;
        }
    }

    // event/{deviceId}
    public static final class Event {
        public final String cap;
        public final int value;

        public Event(String cap, int value) {
            this.cap = cap;
            this.value = value;
        }
    }
}
