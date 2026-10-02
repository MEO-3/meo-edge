package org.thingai.app.meo.api.dto;

// Request body for the stepped provisioning endpoints: connect uses bleAddress,
// setup uses ssid/password.
public class ProvisionRequest {
    private String bleAddress;
    private String ssid;
    private String password;

    public String getBleAddress() {
        return bleAddress;
    }

    public void setBleAddress(String bleAddress) {
        this.bleAddress = bleAddress;
    }

    public String getSsid() {
        return ssid;
    }

    public void setSsid(String ssid) {
        this.ssid = ssid;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
