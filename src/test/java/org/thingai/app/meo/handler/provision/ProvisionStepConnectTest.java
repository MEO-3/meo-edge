package org.thingai.app.meo.handler.provision;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ProvisionStepConnectTest {

    // A wrong fallback either fails provisioning for old firmware or pairs a type with the wrong cap.
    @Test
    void parsesCapTypesOrFallsBackToGeneric() {
        assertArrayEquals(new int[]{1, 5}, ProvisionStepConnect.parseCapTypes(JsonParser.parseString("[1,5]"), 2));
        assertArrayEquals(new int[]{0, 0}, ProvisionStepConnect.parseCapTypes(null, 2));
        assertArrayEquals(new int[]{0, 0}, ProvisionStepConnect.parseCapTypes(JsonParser.parseString("[1]"), 2));
        assertArrayEquals(new int[]{0, 0}, ProvisionStepConnect.parseCapTypes(JsonParser.parseString("[1,\"x\"]"), 2));
    }
}
