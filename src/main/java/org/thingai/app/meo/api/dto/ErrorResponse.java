package org.thingai.app.meo.api.dto;

public class ErrorResponse {
    private int errorCode;
    private String error;

    public ErrorResponse() {
    }

    public ErrorResponse(int errorCode, String error) {
        this.errorCode = errorCode;
        this.error = error;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(int errorCode) {
        this.errorCode = errorCode;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
