package org.thingai.app.meo.handler.cloud;

public interface CloudRegisterCallback {
    void onRegisterSuccess(String edgeId, String secret);
    void onRegisterFailed(int errorCode, String errorMessage);
}
