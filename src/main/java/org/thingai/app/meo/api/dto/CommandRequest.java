package org.thingai.app.meo.api.dto;

// Request body for device control: read or write one device-defined cap.
// value is an int16 and only used by writes.
public class CommandRequest {
    private String cap;
    private String op;
    private int value;

    public String getCap() {
        return cap;
    }

    public void setCap(String cap) {
        this.cap = cap;
    }

    public String getOp() {
        return op;
    }

    public void setOp(String op) {
        this.op = op;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }
}
