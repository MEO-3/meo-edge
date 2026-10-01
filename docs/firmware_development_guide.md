# Firmware Development Guide (Depricated)

> This guide describes the current MEO BLE provisioning contract. It may change as the hardware, firmware, and service architecture evolve.

## Provisioning Overview

MEO devices are expected to ship with firmware already flashed. When Wi-Fi is not configured, the device should enter BLE provisioning mode. The gateway discovers the device over BLE, then uses the Rust `blemqtt` service to read the device identity and write Wi-Fi settings.

Firmware does not talk to MQTT directly during provisioning. MQTT is only used between the Java service and the gateway BLE service.

## BLE Advertising

In provisioning mode, the device should advertise the MEO provisioning service UUID:

```text
7f5a0000-0f23-4b6a-9f5e-3c2a9f7e0100
```

The advertised name may use a setup-friendly prefix such as:

```text
MEO-Setup-
```

The service UUID is the primary discovery filter. The name prefix is optional and should only help humans recognize devices.

## GATT Service

Expose one primary provisioning service:

```text
MEO_DEVICE_PROVISION_SERVICE
7f5a0000-0f23-4b6a-9f5e-3c2a9f7e0100
```

Required characteristics:

| Purpose | UUID | Access |
| --- | --- | --- |
| Device MAC | `7f5a0001-0f23-4b6a-9f5e-3c2a9f7e0100` | Read |
| Network config | `7f5a0002-0f23-4b6a-9f5e-3c2a9f7e0100` | Write |
| Provision status | `7f5a0003-0f23-4b6a-9f5e-3c2a9f7e0100` | Read, Notify |
| Device capabilities | `7f5a0004-0f23-4b6a-9f5e-3c2a9f7e0100` | Read |

The provision status characteristic must support Notify so the gateway can subscribe and receive live join progress instead of polling.

## Data Format

Use UTF-8 strings for this version.

Device MAC read result:

```text
AA:BB:CC:DD:EE:FF
```

Network config write payload:

```json
{
  "ssid": "Classroom WiFi",
  "password": "secret",
  "brokerHost": "192.168.1.10",
  "brokerPort": 1883
}
```

Field rules:

- `ssid` — required.
- `password` — optional (open networks).
- `brokerHost` — required. The gateway's LAN IPv4 address, filled in by the gateway itself
  (auto-detected, never user input). A config without it must be rejected with a `failed` status
  and message `broker host is required`, the same treatment as a missing SSID.
- `brokerPort` — optional, defaults to `1883`.

Provision status read or notify payload:

```json
{
  "state": "connecting"
}
```

Status states, in order:

```text
received
connecting
connected
failed
```

## blemqtt Provision Flow

The Java service calls `blemqtt` commands through MQTT. The firmware only sees normal BLE operations.

1. Gateway scans for devices advertising the provisioning service UUID.
2. Gateway connects to the selected BLE device.
3. Gateway reads the device MAC.
4. Gateway reads the device capabilities characteristic and records the capability set for this device.
5. Gateway subscribes to the provision status characteristic.
6. Gateway writes the network config (Wi-Fi credentials plus its own broker address).
7. Firmware stores the credentials and broker info, then attempts to join Wi-Fi, notifying `received` -> `connecting` -> `connected`/`failed`.
8. Gateway waits for a terminal status notification, then disconnects.
9. Firmware switches to normal online mode after a successful Wi-Fi connection.

## Device Identity

- The BLE address is only a temporary transport address.
- The MAC address is the stable device identity. The gateway records a provisioned device by its MAC.
- Provisioning does not carry a per-product profile. A device does not register a product type with the gateway; it only reports the generic capability set it supports (see Capability Reporting).

## Broker Address

The MQTT broker runs on the gateway, and the broker host is always the gateway's LAN IPv4 address. The gateway detects its own address and writes it in the network config payload — devices never discover the broker themselves (no mDNS), and users never enter it.

The device captures the broker address at provision time and persists it alongside the Wi-Fi credentials. If the gateway's IP address changes, provisioned devices are stranded and must be re-provisioned — give the gateway a DHCP reservation or static IP.

After a successful Wi-Fi join, firmware uses the stored host and port to connect to the broker and enter normal online messaging — see `mqtt_messaging.md` for that contract.

## Device Capabilities

MEO devices no longer ship a per-product "device profile" or capability catalog. Instead, a device exposes what it can do through a **fixed, generic command catalog** shared by both sides:

- Firmware: `meo-arduino/lib/meo/define/Meo3_Cmd.h`
- Gateway: `org.thingai.app.meo.define.MeoCmd`

The two are kept in sync value-for-value, so a command means the same thing on both ends without a per-product translation table. The catalog has generic command verbs (`READ`, `WRITE`, `EXECUTE`, `EXECUTE_WITH_VAR`, `STOP`) and typed read/write targets (for example `READ_TEMP`, `READ_CO2`, `WRITE_LED`, `WRITE_SERVO`). A maker binds a new device to these existing constants rather than defining and registering a new product, so no profile import step is needed.

Keeping the two catalogs in sync is a **cosmetic** concern, not a correctness one: the gateway and UI carry capability IDs verbatim and only look up a friendly label at the display edge. An ID the label table does not recognize is shown as unknown with no control, never dropped.

This runtime command exchange happens over MQTT after the device is online and is separate from the BLE provisioning contract above.

## Capability Reporting

A device does not register a product profile, so the gateway has no way to know **which** catalog entries a particular unit implements until the device tells it. The device reports this **during provisioning**, over a read-only capability characteristic on the provisioning GATT service.

### Contract

The capability set is **fixed per boot** and is captured once, as part of the provisioning flow. The gateway reads the capability characteristic right after the device MAC and records the capability set against the device's MAC.

Because the set is captured at provision time, changing a device's capabilities means re-provisioning it: re-flashing a device removes its old gateway record and forces provisioning again, so the recorded set is always the one the current firmware exposes. Capabilities are intentionally part of the provisioning contract — there is no separate runtime announcement, no retained state, and no online/offline presence tied to this.

The gateway stores the capability list verbatim. It does **not** filter the list against its own catalog — unknown IDs are stored and passed through, to be marked unknown only when displayed.

### Capability characteristic payload

Read result of the capability characteristic. Identity (MAC) comes from its own characteristic, so this payload carries only device metadata and the capability list. Capability IDs come straight from the shared catalog; because the catalog already encodes the meaning of each command, the device reports only the IDs — no per-capability metadata.

```json
{
  "model": "meo-weather-1",
  "fw": "1.2.0",
  "capabilities": [61441, 65281]
}
```

`61441` is `MEO_READ_TEMP` (`0xF001`) and `65281` is `MEO_WRITE_LED` (`0xFF01`). There is no catalog version field: the receiver resolves each ID to a label if it can and shows it as unknown otherwise.

### Declaring capabilities in firmware

The maker declares each capability in the sketch before `begin()`. The capability characteristic value is built from exactly this declared set:

```cpp
MeoDevice device("Weather Station");

void setup() {
  device.addCapability(MEO_READ_TEMP);
  device.addCapability(MEO_WRITE_LED);
  device.begin();   // capability characteristic is built from the declared set
}
```

`addCapability(id)` records one capability ID. Call it once per capability the device supports. `begin()` must have the complete set before it publishes the provisioning service, so all `addCapability` calls belong in `setup()` ahead of `begin()`. Declarations are stored in a small fixed-size array — no dynamic allocation.

## Firmware Behavior

When Wi-Fi is missing or invalid, start BLE provisioning mode automatically.

After receiving Wi-Fi config:

1. Validate the payload.
2. Save credentials only if the payload is valid.
3. Update provision status to `received`.
4. Attempt the Wi-Fi connection.
5. Update status to `connected` or `failed`.
6. On success, stop provisioning mode or reboot into normal mode.

If connection fails, keep BLE provisioning available so the gateway can retry.

## Notes

- Do not require cloud access for provisioning.
- Keep provisioning responses short and easy to parse.
- Keep the provision status characteristic notifying on every state change.
```
