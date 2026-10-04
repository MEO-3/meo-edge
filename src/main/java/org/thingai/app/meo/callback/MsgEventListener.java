package org.thingai.app.meo.callback;

public interface MsgEventListener {
    void onEvent(String deviceId, String cap, int value);
}
