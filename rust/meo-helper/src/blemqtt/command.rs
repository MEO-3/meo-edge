use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum BleMqttOp {
    #[serde(rename = "adapter.power")]
    AdapterPower,
    #[serde(rename = "adapter.status")]
    AdapterStatus,
    #[serde(rename = "scan.start")]
    ScanStart,
    #[serde(rename = "scan.stop")]
    ScanStop,
    #[serde(rename = "device.list")]
    DeviceList,
    #[serde(rename = "device.connect")]
    DeviceConnect,
    #[serde(rename = "device.disconnect")]
    DeviceDisconnect,
    #[serde(rename = "gatt.services")]
    GattServices,
    #[serde(rename = "gatt.read")]
    GattRead,
    #[serde(rename = "gatt.write")]
    GattWrite,
    #[serde(rename = "gatt.subscribe")]
    GattSubscribe,
    #[serde(rename = "gatt.unsubscribe")]
    GattUnsubscribe,
    #[serde(rename = "advertise.start")]
    AdvertiseStart,
    #[serde(rename = "advertise.stop")]
    AdvertiseStop,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BleMqttCommand {
    pub request_id: String,
    pub op: BleMqttOp,
    #[serde(default)]
    pub params: Value,
}
