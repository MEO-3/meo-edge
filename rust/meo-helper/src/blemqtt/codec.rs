use crate::blemqtt::command::BleMqttCommand;
use crate::blemqtt::event::BleMqttEvent;
use crate::blemqtt::reply::BleMqttReply;

pub type CodecResult<T> = Result<T, Box<dyn std::error::Error + Send + Sync>>;

pub fn decode_command(payload: &[u8]) -> CodecResult<BleMqttCommand> {
    Ok(serde_json::from_slice(payload)?)
}

pub fn encode_reply(reply: &BleMqttReply) -> CodecResult<Vec<u8>> {
    Ok(serde_json::to_vec(reply)?)
}

pub fn encode_event(event: &BleMqttEvent) -> CodecResult<Vec<u8>> {
    Ok(serde_json::to_vec(event)?)
}
