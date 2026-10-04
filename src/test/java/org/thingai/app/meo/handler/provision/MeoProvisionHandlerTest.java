package org.thingai.app.meo.handler.provision;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class MeoProvisionHandlerTest {

    // A wrong fallback either fails provisioning for old firmware or pairs a type with the wrong cap.
    @Test
    void parsesCapTypesOrFallsBackToGeneric() {
        assertArrayEquals(new int[]{1, 5}, MeoProvisionHandler.parseCapTypes(JsonParser.parseString("[1,5]"), 2));
        assertArrayEquals(new int[]{0, 0}, MeoProvisionHandler.parseCapTypes(null, 2));
        assertArrayEquals(new int[]{0, 0}, MeoProvisionHandler.parseCapTypes(JsonParser.parseString("[1]"), 2));
        assertArrayEquals(new int[]{0, 0}, MeoProvisionHandler.parseCapTypes(JsonParser.parseString("[1,\"x\"]"), 2));
    }
}
