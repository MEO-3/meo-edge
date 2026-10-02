# Zigbee2MQTT — Device Key Research

> Research notes for a planned Zigbee2MQTT (z2m) bridge integration. Nothing here is implemented
> yet — this is groundwork for the capability-mapping design.

## Headline finding: there is no fixed, universal key set

Zigbee2MQTT does not publish a fixed schema. Each device publishes whatever its own `exposes`
definition declares (visible per-device at `zigbee2mqtt/bridge/devices`, or on that device's page
under zigbee2mqtt.io/devices/). The official docs are explicit about this: *"Each device produces
a different JSON message. To see what your device publishes check the 'Exposes' section on the
device page."* There is no guaranteed common key across all devices — not even `state`, since
pure sensors never expose it.

That said, two properties are common enough in practice to treat as near-universal, with caveats:

| Key | Present on | Caveat |
| --- | --- | --- |
| `linkquality` | Virtually every device (radio diagnostic z2m attaches to outgoing messages) | Not documented as *guaranteed*; can be removed via `filtered_attributes` config. Treat as "expect it, don't depend on it." |
| `battery` | All battery-powered devices (sensors, buttons, most non-mains devices) | Absent on mains-powered devices (bulbs, plugs, relays) |

Everything else is category-specific. The key set has to be read from each device's declared
`exposes` at pairing time — not assumed from a hardcoded table.

## Common keys by device category

| Category | Typical keys | Notes |
| --- | --- | --- |
| **Light (on/off)** | `state` | `ON` / `OFF` / `TOGGLE` |
| **Light (dimmable)** | `state`, `brightness` | `brightness` is 0–254 |
| **Light (color temp)** | `state`, `brightness`, `color_temp` | Mireds, device-specific range (commonly ~150–500); text presets also accepted: `coolest`/`cool`/`neutral`/`warm`/`warmest` |
| **Light (full color)** | `state`, `brightness`, `color` | `color` is a **nested object** — `{"x":.., "y":..}` (CIE xy) or `{"hue":.., "saturation":..}` — not a flat RGB field |
| **Light (effects)** | `effect` | Transient, device-dependent values (`blink`, `breathe`, `okay`, `channel_change`, ...) |
| **Switch / plug** | `state` | Metered plugs may add `power`, `energy`, `voltage`, `current` |
| **Cover / blind** | `state` (`OPEN`/`CLOSE`/`STOP`), `position`, `tilt` | `position` is 0–100 |
| **Lock** | `state` (`LOCK`/`UNLOCK`) | |
| **Climate / thermostat** | `system_mode`, `current_heating_setpoint`, `occupied_heating_setpoint`, `local_temperature`, `running_state`, `fan_mode`, `preset` | `system_mode` values: `off`/`heat`/`cool`/`auto`/`dry`/`fan_only` |
| **Sensors (read-only)** | `temperature`, `humidity`, `illuminance`, `occupancy`, `contact` | Never accept `/set` — push-only, arrive unsolicited whenever the value changes |

## Control (`/set`) syntax rules

- **Full JSON**, one or more properties per publish: topic `zigbee2mqtt/FRIENDLY_NAME/set`, body
  `{"state": "ON", "brightness": 200}`.
- **Single-property shorthand**: publish a bare value (e.g. `ON`, no JSON) to
  `zigbee2mqtt/FRIENDLY_NAME/set/state` — equivalent to the full-JSON form for that one property.
- **Reading a value on demand**: publish `{"state": ""}` (empty value) to
  `zigbee2mqtt/FRIENDLY_NAME/get`. The value itself then arrives on the normal state topic
  (`zigbee2mqtt/FRIENDLY_NAME`), not as a reply on `/get`.
- **`transition`**: an extra key (seconds, float) addable to any light-control payload — applies
  uniformly to on/off, brightness, color_temp, and color changes: `{"brightness": 156, "transition": 3}`.
- **Move/step** (relative adjustment, no explicit target): `{"brightness_step": 20}`,
  `{"color_temp_move": ...}`.

## State (readings) are push-based

Unlike MEO's native devices (which are read on demand), z2m
publishes the **full current state** to `zigbee2mqtt/FRIENDLY_NAME` every time anything changes.
There is no request/reply round trip for reads in normal operation.

## Sources

- [MQTT Topics and Messages](https://www.zigbee2mqtt.io/guide/usage/mqtt_topics_and_messages.html)
- [Exposes](https://www.zigbee2mqtt.io/guide/usage/exposes.html)
- [Allowing devices to join](https://www.zigbee2mqtt.io/guide/usage/pairing_devices.html)
- [Gledopto GL-C-006 control via MQTT](https://www.zigbee2mqtt.io/devices/GL-C-006.html)
- [SONOFF SNZB-03P control via MQTT](https://www.zigbee2mqtt.io/devices/SNZB-03P.html)
