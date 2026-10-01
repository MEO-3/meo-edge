use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AdapterStatus {
    pub available: bool,
    pub name: Option<String>,
    pub powered: bool,
    pub discovering: bool,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BleDevice {
    pub address: String,
    pub name: Option<String>,
    pub rssi: Option<i16>,
    pub service_uuids: Vec<String>,
    pub connected: bool,
    pub paired: bool,
    pub trusted: bool,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScanStartParams {
    pub timeout_ms: Option<u64>,
    pub name_prefix: Option<String>,
    pub service_uuid: Option<String>,
}

impl Default for ScanStartParams {
    fn default() -> Self {
        Self {
            timeout_ms: Some(8_000),
            name_prefix: None,
            service_uuid: None,
        }
    }
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceParams {
    pub address: String,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GattParams {
    pub address: String,
    pub service_uuid: String,
    pub characteristic_uuid: String,
    pub encoding: Option<String>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GattWriteParams {
    pub address: String,
    pub service_uuid: String,
    pub characteristic_uuid: String,
    pub encoding: Option<String>,
    pub value: String,
}

// Read replies and gatt.notification event payloads share this shape.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GattValue {
    pub address: String,
    pub service_uuid: String,
    pub characteristic_uuid: String,
    pub encoding: String,
    pub value: String,
}

pub const ENCODING_UTF8: &str = "utf8";
pub const ENCODING_BASE64: &str = "base64";

pub fn encode_value(bytes: &[u8], encoding: Option<&str>) -> Result<(String, String), String> {
    match encoding.unwrap_or(ENCODING_UTF8) {
        ENCODING_UTF8 => Ok((
            ENCODING_UTF8.to_string(),
            String::from_utf8_lossy(bytes).into_owned(),
        )),
        ENCODING_BASE64 => Ok((
            ENCODING_BASE64.to_string(),
            base64::Engine::encode(&base64::engine::general_purpose::STANDARD, bytes),
        )),
        other => Err(format!("unsupported encoding: {other}")),
    }
}

pub fn decode_value(value: &str, encoding: Option<&str>) -> Result<Vec<u8>, String> {
    match encoding.unwrap_or(ENCODING_UTF8) {
        ENCODING_UTF8 => Ok(value.as_bytes().to_vec()),
        ENCODING_BASE64 => {
            base64::Engine::decode(&base64::engine::general_purpose::STANDARD, value)
                .map_err(|error| format!("invalid base64 value: {error}"))
        }
        other => Err(format!("unsupported encoding: {other}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn utf8_roundtrip_is_default() {
        let (encoding, value) = encode_value(b"{\"state\":\"connected\"}", None).unwrap();
        assert_eq!(encoding, ENCODING_UTF8);
        assert_eq!(value, "{\"state\":\"connected\"}");
        assert_eq!(decode_value(&value, None).unwrap(), b"{\"state\":\"connected\"}");
    }

    #[test]
    fn base64_roundtrip() {
        let bytes = [0u8, 159, 146, 150];
        let (encoding, value) = encode_value(&bytes, Some(ENCODING_BASE64)).unwrap();
        assert_eq!(encoding, ENCODING_BASE64);
        assert_eq!(decode_value(&value, Some(ENCODING_BASE64)).unwrap(), bytes);
    }

    #[test]
    fn unknown_encoding_is_rejected() {
        assert!(encode_value(b"x", Some("hex")).is_err());
        assert!(decode_value("x", Some("hex")).is_err());
    }

    #[test]
    fn gatt_params_parse_camel_case() {
        let params: GattParams = serde_json::from_value(serde_json::json!({
            "address": "AA:BB:CC:DD:EE:FF",
            "serviceUuid": "7f5a0000-0f23-4b6a-9f5e-3c2a9f7e0100",
            "characteristicUuid": "7f5a0001-0f23-4b6a-9f5e-3c2a9f7e0100",
            "encoding": "utf8"
        }))
        .unwrap();
        assert_eq!(params.address, "AA:BB:CC:DD:EE:FF");
        assert_eq!(params.encoding.as_deref(), Some("utf8"));
    }
}
