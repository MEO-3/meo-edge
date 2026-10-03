package org.thingai.app.meo.handler.cloud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudRegisterTest {

    // The cloud rejects any other shape with 400, so a wrong code means the edge can never register.
    @Test
    void claimCodeIsEightUpperAlphanumerics() {
        assertTrue(CloudRegister.generateClaimCode().matches("[A-Z0-9]{8}"));
    }
}
