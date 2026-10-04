# MEO Open Specification: Device and Device capability attributes

## Device attribute.

| Attribute       | Required | Meaning                                                                                                              |
|-----------------|----------|----------------------------------------------------------------------------------------------------------------------|
| `devId`         | yes      | Stable unique identity, assigned at provisioning; never renamed or reused                                            |
| `name`          | no       | Human label given by the user; free text, may change                                                                 |
| `desc`          | no       | Free-text note about the device                                                                                      |
| `macAddr`       | yes      | Hardware MAC address reported by the device                                                                          |
| `transportType` | yes      | How the device reaches the edge; see [Transport type](#transport-type)                                               |
| `model`         | yes      | Product model set by the firmware                                                                                    |
| `fwVersion`     | yes      | Firmware version reported by the device; `0.0.0` if unset                                                            |
| `caps`          | yes      | Ordered list of the device's capabilities; position is the capability's wire index                                   |

### Transport type

| Value | Transport |
|-------|-----------|
| `0`   | Generic   |
| `1`   | Wi-Fi LAN |
| `2`   | BLE       |
| `3`   | Zigbee    |
| `4`   | LoRa      |
| `5`   | Matter    |
| `20`  | RS485     |
| `21`  | RS232     |

## Device capability attribute.

| Attribute | Required | Meaning                                                                         |
|-----------|----------|---------------------------------------------------------------------------------|
| `idx`     | yes      | Wire index of the capability; its position in the device's `caps` list          |
| `type`    | yes      | What the capability is; see [Capability type](#capability-type)                 |
| `name`    | yes      | Unified namespace name of the capability; free-form, unique within the device   |
| `value`   | yes      | Current value of the capability; signed 16-bit integer (`-32768` to `32767`)    |

### Capability type

Real value = `value` × scale. Range is the limit on the raw `value`.

| Value | Type          | Range              | Unit  | Scale | Access     |
|-------|---------------|--------------------|-------|-------|------------|
| `0`   | `generic`     | `(-32768-32767)`   | none  | 1     | read/write |
| `1`   | `switch`      | `(0-1)`            | none  | 1     | read/write |
| `2`   | `brightness`  | `(0-100)`          | `%`   | 1     | read/write |
| `3`   | `hue`         | `(0-360)`          | `deg` | 1     | read/write |
| `4`   | `saturation`  | `(0-100)`          | `%`   | 1     | read/write |
| `5`   | `temperature` | `(-32768-32767)`   | `Cel` | 0.01  | read       |
| `6`   | `humidity`    | `(0-10000)`        | `%RH` | 0.01  | read       |

## Edge to cloud topic.

| Topic                                   | Direction     | Payload                                      | Retained |
|-----------------------------------------|---------------|----------------------------------------------|----------|
| `meo/v1/edge/{edgeId}/status`           | edge → cloud  | `online` / `offline`; `offline` is the last will | yes  |
| `meo/v1/edge/{edgeId}/event/{devId}`    | edge → cloud  | `{"cap": "temp", "value": 2345}`             | no       |
| `meo/v1/edge/{edgeId}/req`              | cloud → edge  | `{"requestId": "...", "op": 12, "args": {}}` | no       |
| `meo/v1/edge/{edgeId}/res/{requestId}`  | edge → cloud  | `{"ok": true, "data": {}}` or `{"ok": false, "error": {"code": 0, "message": "..."}}` | no |

'meo/v1/edge/{edgeId}/dev/{devid}/cap/{capname}'

