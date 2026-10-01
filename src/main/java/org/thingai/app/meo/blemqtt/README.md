# Java blemqtt Client

This package contains the Java MQTT v5 client for the generic `blemqtt` protocol.

The Java edge service should use `BlemqttClient` to talk to the Rust BLE service over MQTT. Java does not call BLE or BlueZ directly.

## Topics

The client publishes commands to:

```text
blemqtt/v1/command
```

It automatically subscribes to:

```text
blemqtt/v1/reply/+
blemqtt/v1/event
```

## Minimal Usage

```java
import org.thingai.app.meo.blemqtt.BlemqttClient;
import org.thingai.app.meo.blemqtt.BlemqttCommand;
import org.thingai.app.meo.blemqtt.BlemqttConfig;
import org.thingai.app.meo.blemqtt.BlemqttOp;

BlemqttClient client = new BlemqttClient(new BlemqttConfig());
client.connect();

BlemqttCommand command = BlemqttCommand.create(BlemqttOp.ADAPTER_STATUS);
client.send(command).thenAccept(reply -> {
    if (reply.isOk()) {
        // Use reply.getResult()
    } else {
        // Use reply.getError()
    }
});
```

## Scan Example

```java
import com.google.gson.JsonObject;

JsonObject params = new JsonObject();
params.addProperty("timeoutMs", 8000);
params.addProperty("namePrefix", "MEO-Setup-");
params.addProperty("serviceUuid", "7f5a0000-0f23-4b6a-9f5e-3c2a9f7e0100");

BlemqttCommand command = BlemqttCommand.create(BlemqttOp.SCAN_START, params);
client.send(command).thenAccept(reply -> {
    if (reply.isOk()) {
        // reply.getResult() contains {"devices": [...]}
    }
});
```

`namePrefix` and `serviceUuid` are optional generic scan filters. If both are present, the BLE service returns only devices matching both filters.

## Adapter Power Example

```java
JsonObject params = new JsonObject();
params.addProperty("enabled", true);

BlemqttCommand command = BlemqttCommand.create(BlemqttOp.ADAPTER_POWER, params);
client.send(command);
```

## Event Listener

```java
client.onEvent(event -> {
    String type = event.getEventType();
    var payload = event.getPayload();
});
```

## Request Lifecycle

Each `send()` call returns a `CompletableFuture<BlemqttReply>`.

The future completes when a reply with the matching `requestId` arrives on:

```text
blemqtt/v1/reply/{requestId}
```

The future fails if the request times out.

## Defaults

```text
brokerUrl = tcp://localhost:1883
clientId = meo-edge-blemqtt
qos = 1
requestTimeoutMillis = 15000
```

These values can be changed through `BlemqttConfig`.
