package org.thingai.app.meo.handler.cloud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloudMqttTest {

    // Paho rejects mqtt:// and mqtts:// outright, so a missed mapping means the link never connects.
    @Test
    void mapsMqttSchemesToPaho() {
        assertEquals("ssl://cloud.example:8883", CloudMqtt.toPahoUri("mqtts://cloud.example:8883"));
        assertEquals("tcp://localhost:1883", CloudMqtt.toPahoUri("mqtt://localhost:1883"));
        assertEquals("ssl://cloud.example:8883", CloudMqtt.toPahoUri("ssl://cloud.example:8883"));
    }
}
