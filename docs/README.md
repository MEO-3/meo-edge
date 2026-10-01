# MEO Edge — Docs Index

## Current

| Doc | Covers |
| --- | --- |
| [`project_specs.md`](project_specs.md) | Project vision, goals, UI/UX direction, open questions. Titled "(Depricated)" in the file itself, but `AGENTS.md` treats it as a living, authoritative doc — read that tag as "may change," not "unused." |
| [`firmware_development_guide.md`](firmware_development_guide.md) | BLE provisioning contract (GATT service/characteristics, Wi-Fi handoff, capability reporting). Same "(Depricated)" caveat as above — it's the authoritative source of truth, referenced from both `meo-arduino` and this service. |
| [`mqtt_messaging.md`](mqtt_messaging.md) | Runtime device MQTT contract: command/reply/event binary frames, topics, delivery rules. |
| [`http_api.md`](http_api.md) | HTTP API reference: devices CRUD, provisioning steps, device control. |

## Integrations

| Doc | Covers |
| --- | --- |
| [`integrations/zigbee2mqtt.md`](integrations/zigbee2mqtt.md) | Research notes for a planned Zigbee2MQTT bridge — device key/exposes survey, control payload syntax, open capability-mapping question. Not yet implemented. |

## Archive

| Doc | Covers |
| --- | --- |
| [`archive/note.md`](archive/note.md) | Early TCP-based device registration model (`device_id`/`transmit_key`, port 8901), superseded by the BLE provisioning flow. Historical reference only. |
