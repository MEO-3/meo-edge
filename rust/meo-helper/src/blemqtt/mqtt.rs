use std::time::Duration;

use rumqttc::v5::mqttbytes::{QoS, v5::Publish};
use rumqttc::v5::{AsyncClient, Event, EventLoop, Incoming};
use serde_json::json;
use tokio::sync::mpsc::UnboundedReceiver;

use crate::blemqtt::codec::{decode_command, encode_event, encode_reply};
use crate::blemqtt::event::BleMqttEvent;
use crate::blemqtt::service::BleMqttService;
use crate::blemqtt::topic::{COMMAND_TOPIC, EVENT_TOPIC, reply_topic};
use crate::log::Log;

pub type MqttResult<T> = Result<T, Box<dyn std::error::Error + Send + Sync>>;

pub struct BleMqttConfig {
    pub broker_host: String,
    pub broker_port: u16,
    pub client_id: String,
    pub qos: QoS,
}

impl Default for BleMqttConfig {
    fn default() -> Self {
        Self {
            broker_host: "localhost".to_string(),
            broker_port: 1883,
            client_id: "meo-helper".to_string(),
            qos: QoS::AtLeastOnce,
        }
    }
}

pub struct BleMqttBridge {
    client: AsyncClient,
    event_loop: EventLoop,
    config: BleMqttConfig,
    service: BleMqttService,
    // Async events (e.g. GATT notifications) produced outside command handling.
    events: UnboundedReceiver<BleMqttEvent>,
    log: Log,
}

impl BleMqttBridge {
    pub fn new(
        client: AsyncClient,
        event_loop: EventLoop,
        config: BleMqttConfig,
        service: BleMqttService,
        events: UnboundedReceiver<BleMqttEvent>,
        log: Log,
    ) -> Self {
        Self {
            client,
            event_loop,
            config,
            service,
            events,
            log,
        }
    }

    pub async fn run(mut self) -> MqttResult<()> {
        self.log.info(
            "blemqtt",
            &format!("listening on {} via {}", COMMAND_TOPIC, self.broker_label()),
        );

        loop {
            tokio::select! {
                polled = self.event_loop.poll() => match polled {
                    // (Re)subscribe on every (re)connect — rumqttc does not
                    // restore subscriptions after an automatic reconnect.
                    Ok(Event::Incoming(Incoming::ConnAck(_))) => {
                        self.client
                            .subscribe(COMMAND_TOPIC, self.config.qos)
                            .await?;
                    }
                    Ok(Event::Incoming(Incoming::Publish(message))) => {
                        self.handle_publish(message).await?;
                    }
                    Ok(_) => {}
                    Err(error) => {
                        self.log
                            .warning("blemqtt", &format!("MQTT event loop error: {error}"));
                        tokio::time::sleep(Duration::from_secs(1)).await;
                    }
                },
                // A None (all senders dropped) is unreachable in practice: the
                // service we own holds the BleManager and with it a sender.
                event = self.events.recv() => if let Some(event) = event {
                    self.publish_event(&event).await?;
                },
            }
        }
    }

    async fn publish_event(&self, event: &BleMqttEvent) -> MqttResult<()> {
        let payload = encode_event(event)?;
        self.client
            .publish(EVENT_TOPIC, self.config.qos, false, payload)
            .await?;
        Ok(())
    }

    async fn handle_publish(&self, message: Publish) -> MqttResult<()> {
        match decode_command(&message.payload) {
            Ok(command) => {
                let reply_topic = reply_topic(&command.request_id);
                self.log.debug(
                    "blemqtt",
                    &format!(
                        "received command topic={}",
                        String::from_utf8_lossy(&message.topic)
                    ),
                );
                let reply = self.service.handle_command(command).await;
                let payload = encode_reply(&reply)?;
                self.client
                    .publish(reply_topic, self.config.qos, false, payload)
                    .await?;
            }
            Err(error) => {
                self.log
                    .warning("blemqtt", &format!("invalid command: {error}"));
                let event = BleMqttEvent::new(
                    "command.invalid",
                    json!({
                        "topic": String::from_utf8_lossy(&message.topic),
                        "message": error.to_string(),
                    }),
                );
                let payload = encode_event(&event)?;
                self.client
                    .publish(EVENT_TOPIC, self.config.qos, false, payload)
                    .await?;
            }
        }

        Ok(())
    }

    fn broker_label(&self) -> String {
        format!("{}:{}", self.config.broker_host, self.config.broker_port)
    }
}
