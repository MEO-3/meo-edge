package org.thingai.app.meo.handler.provision;

// Progress events pushed to the registered listener (the SSE endpoint).
public final class ProvisionEvent {
    public static final String PROVISION_STATUS = "provision.status";
    public static final String SCAN_STARTED = "scan.started";
    public static final String SCAN_DEVICE_FOUND = "scan.device_found";
    public static final String SCAN_COMPLETED = "scan.completed";
    public static final String DEVICE_PERSISTED = "device.persisted";

    private ProvisionEvent() {
    }
}
