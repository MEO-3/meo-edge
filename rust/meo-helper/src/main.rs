mod ble;
mod blemqtt;
mod log;

use ble::BleManager;
use rumqttc::v5::{AsyncClient, MqttOptions};
use std::time::Duration;

type AppResult<T> = Result<T, Box<dyn std::error::Error + Send + Sync>>;

#[tokio::main]
async fn main() -> AppResult<()> {
    let logger = log::Log::default();
    logger.info("main", "MEO 3 Neo BLE service starting");

    let config = blemqtt::BleMqttConfig::default();
    let mut mqtt_options = MqttOptions::new(
        config.client_id.clone(),
        config.broker_host.clone(),
        config.broker_port,
    );
    mqtt_options.set_keep_alive(Duration::from_secs(30));
    let (mqtt_client, event_loop) = AsyncClient::new(mqtt_options, 25);
    // GATT notifications flow from the BLE manager to the bridge, which
    // publishes them on blemqtt/v1/event.
    let (event_tx, event_rx) = tokio::sync::mpsc::unbounded_channel();
    let ble_manager = BleManager::new(log::Log::default(), event_tx);
    let blemqtt_service = blemqtt::BleMqttService::new(ble_manager, log::Log::default());

    let bridge: blemqtt::BleMqttBridge = blemqtt::BleMqttBridge::new(
        mqtt_client,
        event_loop,
        config,
        blemqtt_service,
        event_rx,
        log::Log::default(),
    );
    bridge.run().await
}
