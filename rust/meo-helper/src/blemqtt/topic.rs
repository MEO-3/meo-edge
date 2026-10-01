pub const COMMAND_TOPIC: &str = "blemqtt/v1/command";
pub const EVENT_TOPIC: &str = "blemqtt/v1/event";
pub const STATUS_TOPIC: &str = "blemqtt/v1/status";

pub fn reply_topic(request_id: &str) -> String {
    format!("blemqtt/v1/reply/{request_id}")
}
