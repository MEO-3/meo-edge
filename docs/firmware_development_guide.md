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
- `brokerHost` — required. The gateway's LAN IPv4 address, filled in by the gateway itself (auto-detected, never user input). A config without it must be rejected with a `failed` status and message `broker host is required`, the same treatment as a missing SSID.
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
- Provisioning does not carry a per-product profile. A device does not register a product type with the gateway; it only reports the caps it defines (see Capability Reporting).

## Broker Address

The MQTT broker runs on the gateway, and the broker host is always the gateway's LAN IPv4 address. The gateway detects its own address and writes it in the network config payload — devices never discover the broker themselves (no mDNS), and users never enter it.

The device captures the broker address at provision time and persists it alongside the Wi-Fi credentials. If the gateway's IP address changes, provisioned devices are stranded and must be re-provisioned — give the gateway a DHCP reservation or static IP.

After a successful Wi-Fi join, firmware uses the stored host and port to connect to the broker and enter normal online messaging.

## Capability Reporting

There is no shared catalog: a device defines its own caps by string key and reports them during provisioning, over the read-only capability characteristic. The gateway reads it right after the MAC and stores the list against the device.

```json
{ "model": "meo-weather-1", "fw": "1.2.0", "caps": ["temp", "led"] }
```

- Keys match `[a-z0-9_]{1,32}` and are unique per device. A report that breaks these rules is stored as no caps.
- Array position is the cap's `idx` on the wire, so order matters.
- The list is captured only at provisioning: changing a device's caps means re-provisioning it.
- The payload must fit one BLE attribute (512 bytes).

In firmware, caps are declared with `addCap(key, onWrite, onRead)` in `setup()`, before `begin()`.

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
