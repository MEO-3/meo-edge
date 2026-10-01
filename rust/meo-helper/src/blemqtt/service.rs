use serde_json::{Value, json};

use crate::ble::device::{DeviceParams, GattParams, GattWriteParams};
use crate::ble::{BleManager, ScanStartParams};
use crate::blemqtt::command::{BleMqttCommand, BleMqttOp};
use crate::blemqtt::reply::BleMqttReply;
use crate::log::Log;

const TAG: &str = "blemqtt-service";

pub struct BleMqttService {
    ble: BleManager,
    log: Log,
}

impl BleMqttService {
    pub fn new(ble: BleManager, log: Log) -> Self {
        Self { ble, log }
    }

    pub async fn handle_command(&self, command: BleMqttCommand) -> BleMqttReply {
        self.log.debug(
            TAG,
            &format!(
                "command request_id={} op={:?}",
                command.request_id, command.op
            ),
        );

        match command.op {
            BleMqttOp::AdapterStatus => self.adapter_status(command.request_id).await,
            BleMqttOp::AdapterPower => self.adapter_power(command.request_id, command.params).await,
            BleMqttOp::ScanStart => self.scan_start(command.request_id, command.params).await,
            BleMqttOp::DeviceList => self.device_list(command.request_id).await,
            BleMqttOp::DeviceConnect => {
                self.device_connect(command.request_id, command.params).await
            }
            BleMqttOp::DeviceDisconnect => {
                self.device_disconnect(command.request_id, command.params)
                    .await
            }
            BleMqttOp::GattRead => self.gatt_read(command.request_id, command.params).await,
            BleMqttOp::GattWrite => self.gatt_write(command.request_id, command.params).await,
            BleMqttOp::GattSubscribe => {
                self.gatt_subscribe(command.request_id, command.params).await
            }
            BleMqttOp::GattUnsubscribe => {
                self.gatt_unsubscribe(command.request_id, command.params)
                    .await
            }
            op => BleMqttReply::error(
                command.request_id,
                "blemqtt.unsupported_op",
                format!("operation is not implemented yet: {op:?}"),
            ),
        }
    }

    async fn adapter_status(&self, request_id: String) -> BleMqttReply {
        match self.ble.adapter_status().await {
            Ok(status) => self.ok(request_id, json!(status)),
            Err(error) => self.err(request_id, "ble.adapter_status_failed", error.to_string()),
        }
    }

    async fn adapter_power(&self, request_id: String, params: Value) -> BleMqttReply {
        let enabled = match params.get("enabled").and_then(|value| value.as_bool()) {
            Some(enabled) => enabled,
            None => {
                return self.err(
                    request_id,
                    "blemqtt.invalid_params",
                    "adapter.power requires boolean params.enabled",
                );
            }
        };

        match self.ble.adapter_power(enabled).await {
            Ok(status) => self.ok(request_id, json!(status)),
            Err(error) => self.err(request_id, "ble.adapter_power_failed", error.to_string()),
        }
    }

    async fn scan_start(&self, request_id: String, params: Value) -> BleMqttReply {
        let params = match serde_json::from_value::<ScanStartParams>(params) {
            Ok(params) => params,
            Err(error) => {
                return self.err(request_id, "blemqtt.invalid_params", error.to_string());
            }
        };

        match self.ble.scan_start(params).await {
            Ok(devices) => self.ok(request_id, json!({ "devices": devices })),
            Err(error) => self.err(request_id, "ble.scan_failed", error.to_string()),
        }
    }

    async fn device_list(&self, request_id: String) -> BleMqttReply {
        match self.ble.device_list().await {
            Ok(devices) => self.ok(request_id, json!({ "devices": devices })),
            Err(error) => self.err(request_id, "ble.device_list_failed", error.to_string()),
        }
    }

    async fn device_connect(&self, request_id: String, params: Value) -> BleMqttReply {
        let params = match serde_json::from_value::<DeviceParams>(params) {
            Ok(params) => params,
            Err(error) => {
                return self.err(request_id, "blemqtt.invalid_params", error.to_string());
            }
        };

        match self.ble.device_connect(params).await {
            Ok(device) => self.ok(request_id, json!(device)),
            Err(error) => self.err(request_id, "ble.connect_failed", error.to_string()),
        }
    }

    async fn device_disconnect(&self, request_id: String, params: Value) -> BleMqttReply {
        let params = match serde_json::from_value::<DeviceParams>(params) {
            Ok(params) => params,
            Err(error) => {
                return self.err(request_id, "blemqtt.invalid_params", error.to_string());
            }
        };

        let address = params.address.clone();
        match self.ble.device_disconnect(params).await {
            Ok(()) => self.ok(request_id, json!({ "address": address })),
            Err(error) => self.err(request_id, "ble.disconnect_failed", error.to_string()),
        }
    }

    async fn gatt_read(&self, request_id: String, params: Value) -> BleMqttReply {
        let params = match serde_json::from_value::<GattParams>(params) {
            Ok(params) => params,
            Err(error) => {
                return self.err(request_id, "blemqtt.invalid_params", error.to_string());
            }
        };

        match self.ble.gatt_read(params).await {
            Ok(value) => self.ok(request_id, json!(value)),
            Err(error) => self.err(request_id, "ble.gatt_read_failed", error.to_string()),
        }
    }

    async fn gatt_write(&self, request_id: String, params: Value) -> BleMqttReply {
        let params = match serde_json::from_value::<GattWriteParams>(params) {
            Ok(params) => params,
            Err(error) => {
                return self.err(request_id, "blemqtt.invalid_params", error.to_string());
            }
        };

        let address = params.address.clone();
        let characteristic_uuid = params.characteristic_uuid.clone();
        match self.ble.gatt_write(params).await {
            Ok(()) => self.ok(
                request_id,
                json!({ "address": address, "characteristicUuid": characteristic_uuid }),
            ),
            Err(error) => self.err(request_id, "ble.gatt_write_failed", error.to_string()),
        }
    }

    async fn gatt_subscribe(&self, request_id: String, params: Value) -> BleMqttReply {
        let params = match serde_json::from_value::<GattParams>(params) {
            Ok(params) => params,
            Err(error) => {
                return self.err(request_id, "blemqtt.invalid_params", error.to_string());
            }
        };

        let address = params.address.clone();
        let service_uuid = params.service_uuid.clone();
        let characteristic_uuid = params.characteristic_uuid.clone();
        match self.ble.gatt_subscribe(params).await {
            Ok(()) => self.ok(
                request_id,
                json!({
                    "address": address,
                    "serviceUuid": service_uuid,
                    "characteristicUuid": characteristic_uuid,
                    "subscribed": true
                }),
            ),
            Err(error) => self.err(request_id, "ble.gatt_subscribe_failed", error.to_string()),
        }
    }

    async fn gatt_unsubscribe(&self, request_id: String, params: Value) -> BleMqttReply {
        let params = match serde_json::from_value::<GattParams>(params) {
            Ok(params) => params,
            Err(error) => {
                return self.err(request_id, "blemqtt.invalid_params", error.to_string());
            }
        };

        let address = params.address.clone();
        let characteristic_uuid = params.characteristic_uuid.clone();
        match self.ble.gatt_unsubscribe(params).await {
            Ok(()) => self.ok(
                request_id,
                json!({ "address": address, "characteristicUuid": characteristic_uuid }),
            ),
            Err(error) => self.err(request_id, "ble.gatt_unsubscribe_failed", error.to_string()),
        }
    }

    fn ok(&self, request_id: String, result: Value) -> BleMqttReply {
        self.log
            .debug(TAG, &format!("reply ok request_id={request_id}"));
        BleMqttReply::ok(request_id, result)
    }

    fn err(
        &self,
        request_id: String,
        code: impl Into<String>,
        message: impl Into<String>,
    ) -> BleMqttReply {
        let code = code.into();
        let message = message.into();
        self.log.warning(
            TAG,
            &format!("reply error request_id={request_id} code={code} message={message}"),
        );
        BleMqttReply::error(request_id, code, message)
    }
}
