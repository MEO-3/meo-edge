# blemqtt

`blemqtt` is a generic BLE-over-MQTT v5 control interface. It exposes BLE adapter, scan, device, GATT, and advertising operations through MQTT topics.

This module should stay project-neutral. MEO-specific provisioning logic should be built by sending generic `blemqtt` commands, not by adding MEO concepts into this protocol.

## Topic Contract

The MQTT v5 broker is expected to run locally on the gateway, so topics do not include a gateway ID.

```text
blemqtt/v1/command
blemqtt/v1/reply/{request_id}
blemqtt/v1/event
blemqtt/v1/status
```

Use `blemqtt/v1/command` for requests. The service replies to `blemqtt/v1/reply/{request_id}`. Events that are not direct replies, such as scan results or notifications, are published to `blemqtt/v1/event`.

## Command Envelope

Every command should include a caller-provided `requestId`.

```json
{
  "requestId": "req-001",
  "op": "adapter.status",
  "params": {}
}
```

`requestId` lets the caller match an async MQTT reply to the command that caused it.

## Supported Operation Names

Initial protocol operation names:

```text
adapter.power
adapter.status
scan.start
scan.stop
device.list
device.connect
device.disconnect
gatt.services
gatt.read
gatt.write
gatt.subscribe
gatt.unsubscribe
advertise.start
advertise.stop
```

Currently implemented operations:

```text
adapter.status
adapter.power
scan.start
device.list
device.connect
device.disconnect
gatt.read
gatt.write
gatt.subscribe
gatt.unsubscribe
```

`gatt.read`, `gatt.write`, and `gatt.subscribe` accept an optional `encoding` of `utf8`
(default) or `base64`; replies and notification payloads echo the encoding used.

Other operations should return a structured `blemqtt.unsupported_op` error until their BLE backend is added.

## Replies

Successful reply:

```json
{
  "requestId": "req-001",
  "ok": true,
  "result": {
    "powered": true
  }
}
```

Error reply:

```json
{
  "requestId": "req-001",
  "ok": false,
  "error": {
    "code": "blemqtt.unsupported_op",
    "message": "operation is not implemented yet"
  }
}
```

## Events

Events are published when something happens outside a direct command reply.

Example scan result:

```json
{
  "type": "scan.device_found",
  "payload": {
    "address": "AA:BB:CC:DD:EE:FF",
    "name": "MEO-Setup-ABCD",
    "rssi": -61,
    "connectable": true
  }
}
```

Example GATT notification:

```json
{
  "type": "gatt.notification",
  "payload": {
    "address": "AA:BB:CC:DD:EE:FF",
    "serviceUuid": "7c9e0000-4f8a-4f9b-9b3c-2c7e1d000001",
    "characteristicUuid": "7c9e0003-4f8a-4f9b-9b3c-2c7e1d000001",
    "encoding": "utf8",
    "value": "{\"state\":\"joined_wifi\"}"
  }
}
```

## Example Commands

Turn on the adapter:

```json
{
  "requestId": "req-power-001",
  "op": "adapter.power",
  "params": {
    "enabled": true
  }
}
```

Start scanning:

```json
{
  "requestId": "req-scan-001",
  "op": "scan.start",
  "params": {
    "timeoutMs": 8000,
    "namePrefix": "MEO-Setup-",
    "serviceUuid": "7f5a0000-0f23-4b6a-9f5e-3c2a9f7e0100"
  }
}
```

`scan.start` filters are optional. `namePrefix` filters by advertised device name. `serviceUuid` filters by advertised service UUID. If both are provided, both must match. If neither is provided, discovery returns all devices found during the timeout.

Connect to a device:

```json
{
  "requestId": "req-connect-001",
  "op": "device.connect",
  "params": {
    "address": "AA:BB:CC:DD:EE:FF"
  }
}
```

Read a characteristic:

```json
{
  "requestId": "req-read-001",
  "op": "gatt.read",
  "params": {
    "address": "AA:BB:CC:DD:EE:FF",
    "serviceUuid": "7c9e0000-4f8a-4f9b-9b3c-2c7e1d000001",
    "characteristicUuid": "7c9e0001-4f8a-4f9b-9b3c-2c7e1d000001",
    "encoding": "utf8"
  }
}
```

Write a characteristic:

```json
{
  "requestId": "req-write-001",
  "op": "gatt.write",
  "params": {
    "address": "AA:BB:CC:DD:EE:FF",
    "serviceUuid": "7c9e0000-4f8a-4f9b-9b3c-2c7e1d000001",
    "characteristicUuid": "7c9e0002-4f8a-4f9b-9b3c-2c7e1d000001",
    "encoding": "utf8",
    "value": "{\"ssid\":\"Classroom WiFi\"}"
  }
}
```

## How MEO Uses blemqtt

MEO provisioning should be implemented as a higher-level workflow outside this protocol:

1. Send `adapter.power`.
2. Send `scan.start` with the MEO provisioning `serviceUuid` and optional `namePrefix`.
3. Listen for `scan.device_found` events.
4. Send `device.connect`.
5. Send `gatt.read` to read device info.
6. Send `gatt.write` to write Wi-Fi or gateway configuration.
7. Send `gatt.subscribe` to receive provisioning status notifications.

This keeps `blemqtt` reusable for future projects and for non-MEO BLE devices.
