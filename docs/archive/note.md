# Note for developers (Depricated)

> **Archived.** This describes an early TCP-based device registration model (`device_id`/`transmit_key` issued over port 8901) that predates and has been fully superseded by the BLE provisioning + MQTT flow described in `../project_specs.md` and `../firmware_development_guide.md`. Kept for historical reference only — do not implement against this.

## MEO Acronyms and philosophy

### MEO Acronyms
- Device ID (or `device_id`): The `device_id` is a unique identifier assigned to each MEO device. It is used for identifying which device is recognized by MEO open service for communication and data exchange purposes. The device ID is generated with device MAC address and unix timestamp (4 bytes), and generated on the service side when the device first registers with MEO open service.

- Device transmit key (or `transmit_key`): The `transmit_key` is a unique key generated and assigned to each MEO device, tt is used for identifying which device is recognized by MEO open service for communication and data exchange purposes. The transmit key is generated with device MAC address and unix timestamp, and generated on the service side when the device first registers with MEO open service.

### MEO Philosophy
- Device registration: MEO Neo Gateway on first, other devices on after
  - After gateway is booted and Node-RED is on, other devices broadcast registration request to MEO open service (with device MAC address and local IP)
  - MEO open service generates device ID and transmit key for each device, and sends back to the devices via TCP connection. In the registration process, devices remain listening on TCP port 8901 for registration response from MEO open service, then store the received device ID and transmit key in local storage (e.g., EEPROM or flash memory) for future use.
  - After registration, devices start to subscribe to MQTT broker with MEO open service mDNS address (meo-open-service.local) and publish data to MEO open service via MQTT protocol.
  - Topic naming convention:
    - For event publishing: `meo/{device_id}/event`
    - For feature invoking: `meo/{device_id}/feature/{feature_name}/invoke`
- Device event and feature model
  - Event: Device publishes event data to MEO open service via MQTT protocol. Event data is in JSON format, containing event name and event payload.
  - Feature: MEO open service can invoke device features via MQTT protocol. Feature invocation request is in JSON format, containing feature name and feature parameters. Device processes the feature invocation request and sends back the result to MEO open service via MQTT protocol.