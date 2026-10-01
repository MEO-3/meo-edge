# MQTT Device Messaging

> This document is the authoritative contract for runtime MQTT messaging between the MEO open
> service (gateway) and provisioned MEO devices: device control (commands) and device state
> (replies and events). It may change as the platform evolves.

This contract applies **after** provisioning, once a device is on Wi-Fi and connected to the
gateway's MQTT broker. BLE provisioning is a separate contract — see
`firmware_development_guide.md`. It is also separate from `blemqtt` (`blemqtt/v1/...`), which is
the gateway-internal channel between the Java service and the Rust BLE service.

The device learns the broker address (the gateway's LAN IPv4) during BLE provisioning — see the
network config payload in `firmware_development_guide.md`.

Out of scope for this version (deliberately deferred, not forgotten):

- **Device online/offline presence** — no status topic, no Last Will.
- **Broker credentials** — the broker is an open local listener for now; per-device
  authentication is not part of this contract yet.

## Identities

- `deviceId` — the device's stable identity, its Wi-Fi MAC as recorded at provisioning time,
  normalized to lowercase hex without separators (e.g. `AA:BB:CC:DD:EE:FF` → `aabbccddeeff`).
  The gateway stores it in this form, so it drops straight into a topic with no conversion; the
  device row's `macAddress` keeps the readable colon form for display.
- `requestId` — a `uint16` correlating one command with its reply. Opaque to the device; it must be
  echoed back verbatim. It is not a unique ID, and **each publisher owns its own correlation** —
  there is more than one, so a `requestId` is only meaningful to whoever minted it:
  - The **Java service** uses a wrapping counter. Its reply timeout keeps the correlation window
    short enough that reuse is safe.
  - **Node-RED** uses a single fixed value (`0xFFFF`), because an independent counter would collide
    with the service's — both restart at 0. It therefore correlates on the reply's echoed `cap`
    instead, and attributes a failure reply (which carries `cap=0`) to its oldest outstanding
    command for that device.

  A publisher must ignore a reply it has no pending request for: with several publishers on one
  broker, everybody sees everybody's replies on the wildcard.

## Topics

One topic namespace per device under a versioned prefix:

| Topic | Direction | QoS | Retained |
| --- | --- | --- | --- |
| `meo/v1/device/{deviceId}/command` | gateway → device | 1 | no |
| `meo/v1/device/{deviceId}/reply` | device → gateway | 0 | no |
| `meo/v1/device/{deviceId}/event` | device → gateway | 0 | no |

The device subscribes to exactly one topic — its own `command` — and publishes to two fixed
topic strings. No dynamic topic construction is needed in firmware.

The gateway subscribes with wildcards:

```text
meo/v1/device/+/reply
meo/v1/device/+/event    (not consumed yet — see below)
```

There is no retained state topic. The gateway reads device state on demand by sending a READ
command and returning the reply's value.

**Node-RED consumes the event topic.** The `meo-event` node in the `node-red-meo` fork subscribes
to `meo/v1/device/+/event` on the broker and decodes the frame in JS — one connection per
`meo-gateway` config node, shared by every event node using it. This is how edge-triggered
occurrences such as `MEO_EVENT_BUTTON` are observed; they have no READ equivalent to poll for.

Events are QoS 0 and nothing is retained, so a subscriber that is restarting or not yet deployed
misses whatever arrives meanwhile, and nothing is replayed afterwards. A last-value cache is still
deferred; every read costs a device round trip.

## Payloads

All payloads are **fixed-size binary frames**, little-endian, one message per publish — no JSON,
no length prefixes, no framing. Firmware parses them as plain byte offsets (no JSON library
needed for this contract); the gateway mirrors that with a fixed `ByteBuffer` layout
(`ByteOrder.LITTLE_ENDIAN`).

Capability IDs (`cap`) come from the shared command catalog — `MeoCmd.java` on the gateway,
`Meo3_Cmd.h` in firmware — and are carried as a raw `uint16`.

A message whose length does not match its topic's fixed frame size is malformed and must be
dropped with no reply — the frame is too short to trust any field, including `requestId`.

### Command (gateway → device) — 8 bytes

| Offset | Size | Field | Type | Meaning |
| --- | --- | --- | --- | --- |
| 0 | 2 | `requestId` | `uint16` LE | Correlation ID, echoed in the reply |
| 2 | 2 | `cap` | `uint16` LE | Capability to invoke, from the shared catalog |
| 4 | 4 | `value` | `int32` LE | Scalar value for `MEO_WRITE_*`; `0` and ignored otherwise |

There is no separate verb field — the capability ID itself encodes the action:

- `MEO_READ_*` capabilities read a value; the reply carries it. `value` is ignored.
- `MEO_WRITE_*` capabilities write `value` to an actuator.
- `MEO_CMD_*` capabilities are generic device commands (e.g. STOP). Every MEO firmware supports
  them implicitly; they are not declared during provisioning. `value` is ignored.

Example — `requestId=42`, `cap=65281` (`MEO_WRITE_LED`), `value=1`:

| Offset 0-1 | Offset 2-3 | Offset 4-7 |
| --- | --- | --- |
| `0x002A` | `0xFF01` | `0x00000001` |

`value` is always a **single 32-bit integer**. Multi-parameter actuators pack their parameters
into it with the encoding documented in the capability catalog (e.g. `MEO_WRITE_LED_RGB` takes
`0xRRGGBB`, which fits in the low 24 bits). Per-capability parameter schemas are intentionally
avoided.

### Reply (device → gateway) — 10 bytes

The device must publish exactly one reply per received command, echoing the `requestId`.

| Offset | Size | Field | Type | Meaning |
| --- | --- | --- | --- | --- |
| 0 | 2 | `requestId` | `uint16` LE | Echoed from the command |
| 2 | 1 | `ok` | `uint8` (0/1) | Whether the command was accepted and executed |
| 3 | 2 | `cap` | `uint16` LE | Echoed target capability; `0` on failure |
| 5 | 4 | `value` | `int32` LE or `float32` LE | See below; `0` on failure |
| 9 | 1 | `error` | `uint8` | See `MeoCmdErrCode.java`: 1 = bad request, 2 = unknown capability, 3 = handle failed; `0` when `ok=1` |

`value`'s encoding on success depends on which table matched the command's `cap`:

- `MEO_WRITE_*` → `int32` LE, the value that was written.
- `MEO_READ_*` → `float32` LE, the value that was read.

Example — success, write, `requestId=42`, `cap=65281`, `value=1`:

| Offset 0-1 | Offset 2 | Offset 3-4 | Offset 5-8 | Offset 9 |
| --- | --- | --- | --- | --- |
| `0x002A` | `1` | `0xFF01` | `0x00000001` (int32) | `0` |

Example — success, read, `requestId=43`, `cap=61441`, `value=23.5`:

| Offset 0-1 | Offset 2 | Offset 3-4 | Offset 5-8 | Offset 9 |
| --- | --- | --- | --- | --- |
| `0x002B` | `1` | `0xF001` | `23.5` (float32) | `0` |

Example — failure, `requestId=45`, unknown capability:

| Offset 0-1 | Offset 2 | Offset 3-4 | Offset 5-8 | Offset 9 |
| --- | --- | --- | --- | --- |
| `0x002D` | `0` | `0x0000` | `0x00000000` | `2` |

A command targeting a capability the device did not declare at provisioning must be answered with
`ok=0` and an error code — never silently dropped.

### Event (device → gateway) — 6 bytes

Unsolicited messages from the device: periodic sensor readings and edge-triggered occurrences
(e.g. a button press).

| Offset | Size | Field | Type | Meaning |
| --- | --- | --- | --- | --- |
| 0 | 2 | `cap` | `uint16` LE | Capability the value belongs to |
| 2 | 4 | `value` | `float32` LE | Reading or event value |

Event `value` is always `float32` — events only ever carry `MEO_READ_*` readings or `MEO_EVENT_*`
occurrences, never a `MEO_WRITE_*` result, so there is no int/float ambiguity to resolve per-cap.

Example — `cap=61441` (`MEO_READ_TEMP`), `value=23.5`:

| Offset 0-1 | Offset 2-5 |
| --- | --- |
| `0xF001` | `23.5` (float32) |

Events carry **no timestamp** — devices have no reliable clock. The gateway stamps arrival time
when it records the value.

## Delivery rules

- Commands use QoS 1. Replies and events are published at QoS 0 — the firmware MQTT library
  (PubSubClient) can only publish at QoS 0; the gateway's reply timeout covers a lost reply.
  Nothing is retained.
- The gateway correlates replies by `requestId` and applies a timeout; a command with no reply
  within the timeout is reported as failed to the caller. Because commands are QoS 1, firmware
  should treat command handling as effectively idempotent where possible.
- A reply with an unknown `requestId` (e.g. arriving after timeout) is logged and dropped by the
  gateway.
- A command frame that isn't exactly 8 bytes is dropped with no reply — `requestId` can't be
  trusted. A well-formed frame with `cap=0` gets `ok=0` / `error=1` (bad request), since
  `requestId` is recoverable in that case.

## Responsibilities

Gateway (Java service):

- Publishes commands and awaits replies (`requestId` → future, with timeout), mirroring the
  `BlemqttClient` pattern.
- Subscribes to the `reply` wildcard and exposes device control over the HTTP API.

Node-RED (`node-red-meo`):

- Publishes command frames and correlates the replies itself (`meo-command`), over one connection
  per `meo-gateway` config node. It uses the HTTP API only for the device registry.
- Subscribes to the `event` wildcard on the same connection and decodes the event frame, so flows
  can react to what a device pushes (`meo-event`).

Firmware (`meo-arduino`):

- After Wi-Fi is up, connects to the broker, subscribes to its `command` topic, and dispatches
  commands to maker-registered capability handlers.
- Builds and publishes the reply for every command automatically.
- Exposes a simple publish API for readings/events (`cap` + `value`).
