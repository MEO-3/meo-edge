# MEO Edge — HTTP API

Base URL: `http://<gateway>:7070` (port from `MEO_SERVICE_PORT`, default `7070`). All request and
response bodies are JSON. CORS is open (any host).

## Error model

Failed requests return an HTTP error status with a `MeoErrorResponse` body. `errorCode` is a
MEO application code (see `define/MeoErr.java`; control errors use `define/MeoMsgErr.java`), independent of the HTTP status:

```json
{ "errorCode": 200, "error": "device not found" }
```

| Code | Meaning |
| --- | --- |
| 0 | Generic error |
| 100–104 | Provisioning: generic / scan / connect / setup / persist failed |
| 200 | Device not found |
| 201 | Device update failed |
| 1 | Control: bad request |
| 2 | Control: device has no such capability |
| 3 | Control: device failed to run the command |
| 4 | Control: capability does not support this op |
| 5 | Control: device did not reply in time (edge only) |
| 6 | Control: could not send, or messaging unavailable (edge only) |

## Health

### `GET /`

Liveness check. Returns `"meow"`.

## Devices

CRUD over provisioned devices. Devices are **created by the provisioning flow** (below); these
endpoints list, edit user metadata, and remove them.

Device responses use the `MeoDeviceResponse` read model — the device row joined with its
capability ids:

```json
{
  "deviceId": "AA:BB:CC:DD:EE:FF",
  "name": "kitchen sensor",
  "description": "on the shelf",
  "macAddress": "AA:BB:CC:DD:EE:FF",
  "deviceType": 2,
  "transportType": 1,
  "model": "meo-c3",
  "fwVersion": "1.0.0",
  "capabilities": [1, 3]
}
```

### `GET /api/v1/devices`

List all provisioned devices. Returns `MeoDeviceResponse[]` (empty array when none).

### `GET /api/v1/devices/{deviceId}`

Get one device. `404` with `errorCode` 200 if unknown.

### `PUT /api/v1/devices/{deviceId}`

Update a device's **user metadata only**: `name`, `description`, `deviceType`. Identity
(`deviceId`, `macAddress`) and firmware-reported fields (`model`, `fwVersion`, `transportType`)
are owned by the provisioning flow — if present in the body they are ignored. Returns the updated
`MeoDeviceResponse`; `404` if unknown.

```json
{ "name": "kitchen sensor", "description": "on the shelf", "deviceType": 2 }
```

### `DELETE /api/v1/devices/{deviceId}`

Delete a device and its capability rows. Returns the deleted `MeoDeviceResponse`; `404` if unknown.

## Provisioning

Stepped BLE provisioning: **scan → connect → setup → persist**. One in-flight session is shared
across the steps (BLE is single-device). The step calls block while the gateway's BLE service does
the work; open the SSE stream first to watch progress live.

### `GET /api/v1/provision/scan`

Step 1 — scan for nearby MEO devices advertising the provisioning BLE service.

Query parameters:

| Name | Type | Description |
| --- | --- | --- |
| `timeoutMs` | int | Scan duration in milliseconds (default `8000`) |
| `namePrefix` | string | Only return devices whose name starts with this prefix |

Returns the discovered devices as reported by the BLE service. `500` on scan failure.

### `POST /api/v1/provision/connect`

Step 2 — connect to a scanned device over BLE and read its identity (MAC address, model, firmware
version, capabilities).

```json
{ "bleAddress": "<address from a scan result>" }
```

Returns the provisioning session (`MeoDeviceProvision`). `400` if `bleAddress` is missing, `500`
on connect failure.

### `POST /api/v1/provision/setup`

Step 3 — write Wi-Fi credentials to the connected device and wait for its provision status.

```json
{ "ssid": "MyNetwork", "password": "optional for open networks" }
```

Returns the updated session. `400` if `ssid` is missing; `409` if the device rejected the
configuration or no session is in flight.

### `POST /api/v1/provision/persist`

Step 4 — save the successfully provisioned device to the gateway database and close the session.
No request body. Returns the persisted device as `MeoDeviceResponse`; `409` if there is no
provisioned device in flight.

### `GET /api/v1/provision/events` (SSE)

Server-sent events mirroring provisioning progress. Open the stream, then drive the step calls and
watch:

| Event | Payload |
| --- | --- |
| `scan.started` / `scan.completed` | scan lifecycle |
| `scan.device_found` | a discovered device |
| `provision.status` | session snapshot (`MeoDeviceProvision`) on every status change |
| `device.persisted` | the saved device |

On connect, the current in-flight session (if any) is replayed as a `provision.status` event, so
late or reconnecting clients see where the flow stands.

`MeoDeviceProvision.status` values (see `define/ProvisionStatus.java`): 0 generic, 1 created,
2 scanning, 3 connecting BLE, 4 connected, 5 reading MAC, 6 reading capabilities, 7 writing Wi-Fi,
8 reading status, 9 disconnecting, 10 disconnected, 11 **failed**, 12 **provisioned**.

## Control

One endpoint drives every device. There is **no verb**: the capability id encodes the action, so the
same call reads a sensor, writes an actuator, or runs a generic command depending on which id you
send (see `define/MeoCmd.java`).

The gateway publishes an MQTT command frame to the device and blocks for its reply
(see `docs/mqtt_messaging.md`), owning request correlation and the timeout on the caller's behalf.

This is one way to command a device, not the only one: a client that already speaks MQTT can
publish the command frame itself, which is what the Node-RED `meo-command` node does. Use this
endpoint when you would rather not implement frame encoding and reply correlation.

### `POST /api/v1/devices/{deviceId}/command`

```json
{ "cap": 65281, "value": 1 }
```

| Field | Type | Description |
| --- | --- | --- |
| `cap` | int | Capability id, `0`–`65535`. JSON has no hex literal, so send it in **decimal** |
| `value` | int | Only read for WRITE capabilities; ignored by reads and generic commands |

Returns `MeoCommandResponse` — the value the device reported back, with `deviceId` and `cap` echoed
so a caller firing several commands can tell the replies apart:

```json
{ "deviceId": "AA:BB:CC:DD:EE:FF", "cap": 65281, "value": 1.0 }
```

`value` is decoded per capability kind: READ replies carry **float32** (`61441` → `23.5` °C),
everything else **int32**.

Reading a temperature sensor:

```bash
curl -X POST http://<gateway>:7070/api/v1/devices/AA:BB:CC:DD:EE:FF/command \
  -H 'Content-Type: application/json' -d '{ "cap": 61441 }'
```

The call blocks until the device replies, up to **10 seconds**.

#### Capability ids

Values are the authoritative list in `define/MeoCmd.java`; the labels are what a child sees in the
Node-RED palette (`lib/meo-caps.js`) and the firmware's `Meo3_Cmd.h` carries the same numbers. The id
range decides the kind — that is why there is no verb field.

| Range | Kind | Reply value | `value` used |
| --- | --- | --- | --- |
| `0x0001`–`0x0006` | Generic command | int32 | only by `0x0005` |
| `0xE000`–`0xEFFF` | Event | — | **not commandable** (device → gateway only) |
| `0xF000`–`0xFEFF` | Read | float32 | no |
| `0xFF00`–`0xFFFF` | Write | int32 | yes |

| Hex | Decimal | Label |
| --- | --- | --- |
| `0x0001` | 1 | Generic command |
| `0x0002` | 2 | Write |
| `0x0003` | 3 | Read |
| `0x0004` | 4 | Run |
| `0x0005` | 5 | Run with value |
| `0x0006` | 6 | Stop |
| `0xE000` | 57344 | Generic event |
| `0xE001` | 57345 | Button |
| `0xF000` | 61440 | Generic reading |
| `0xF001` | 61441 | Temperature |
| `0xF002` | 61442 | Humidity |
| `0xF003` | 61443 | Pressure |
| `0xF004` | 61444 | CO2 |
| `0xF005` | 61445 | Fine dust (PM2.5) |
| `0xF006` | 61446 | Distance |
| `0xFF00` | 65280 | Generic output |
| `0xFF01` | 65281 | Built-in LED |
| `0xFF02` | 65282 | RGB LED |
| `0xFF03` | 65283 | Buzzer |
| `0xFF04` | 65284 | Motor |
| `0xFF05` | 65285 | Servo |

A device's own `capabilities` array (`GET /api/v1/devices/{deviceId}`) lists what it implements — but
the device is the final authority: an unimplemented id is rejected by the firmware, not by the gateway.

#### Status codes

| Status | `errorCode` | When |
| --- | --- | --- |
| `200` | — | Device replied; body is `MeoCommandResponse` |
| `400` | 300 | Missing body, `cap` out of range, or an event id (`0xE000`–`0xEFFF`) |
| `400` | 301 | Device does not implement the capability |
| `404` | 200 | Unknown device |
| `502` | 303 | Device rejected the command or failed to execute it |
| `503` | 300 | Device messaging is not connected (MQTT unavailable at startup) |
| `504` | 302 | Device did not reply within 10 s |

The device-side reason behind `502` comes from the firmware's error code (`define/MeoMsgErr.java`):
1 malformed request, 2 unknown capability, 3 handler failed. Code 2 is surfaced as `400`/301 rather
than `502`, since the caller — not the device — has to fix it.
