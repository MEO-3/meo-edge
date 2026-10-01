use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use bluer::gatt::remote::Characteristic;
use bluer::{
    Adapter, AdapterEvent, Address, Device, DiscoveryFilter, DiscoveryTransport, Session, Uuid,
};
use futures_util::StreamExt;
use tokio::sync::mpsc::UnboundedSender;
use tokio::task::JoinHandle;

use crate::ble::device::{
    AdapterStatus, BleDevice, DeviceParams, GattParams, GattValue, GattWriteParams, ScanStartParams,
    decode_value, encode_value,
};
use crate::ble::error::BleResult;
use crate::blemqtt::event::BleMqttEvent;
use crate::log::Log;

const TAG: &str = "ble";
const CONNECT_ATTEMPTS: u32 = 3;
const SERVICES_RESOLVED_TIMEOUT: Duration = Duration::from_secs(10);
const SERVICES_RESOLVED_POLL: Duration = Duration::from_millis(100);

pub struct BleManager {
    log: Log,
    // Async events (GATT notifications) flow out through this channel; the MQTT
    // bridge publishes them to blemqtt/v1/event.
    events: UnboundedSender<BleMqttEvent>,
    // Live notify-forwarder tasks keyed by (address, characteristicUuid).
    subscriptions: Mutex<HashMap<(String, String), JoinHandle<()>>>,
}

impl BleManager {
    pub fn new(log: Log, events: UnboundedSender<BleMqttEvent>) -> Self {
        Self {
            log,
            events,
            subscriptions: Mutex::new(HashMap::new()),
        }
    }

    pub async fn adapter_status(&self) -> BleResult<AdapterStatus> {
        let session = Session::new().await?;
        let adapter = match self.default_adapter_if_available(&session).await? {
            Some(adapter) => adapter,
            None => {
                self.log.warning(TAG, "no BLE adapter available");
                return Ok(AdapterStatus {
                    available: false,
                    name: None,
                    powered: false,
                    discovering: false,
                });
            }
        };

        let status = self.adapter_status_from_adapter(&adapter).await?;
        self.log.debug(TAG, &format!("adapter status: {status:?}"));
        Ok(status)
    }

    pub async fn adapter_power(&self, enabled: bool) -> BleResult<AdapterStatus> {
        let adapter = self.adapter().await?;
        self.log.info(
            TAG,
            &format!("setting adapter {} powered={enabled}", adapter.name()),
        );
        adapter.set_powered(enabled).await?;
        self.adapter_status_from_adapter(&adapter).await
    }

    pub async fn scan_start(&self, params: ScanStartParams) -> BleResult<Vec<BleDevice>> {
        let timeout = Duration::from_millis(params.timeout_ms.unwrap_or(8_000));
        let name_prefix = params.name_prefix;
        let service_uuid = match params.service_uuid.as_deref() {
            Some(value) if !value.is_empty() => Some(value.parse::<Uuid>()?),
            _ => None,
        };
        let adapter = self.adapter().await?;

        if !adapter.is_powered().await? {
            self.log.info(TAG, "powering adapter before scan");
            adapter.set_powered(true).await?;
        }

        self.log.info(
            TAG,
            &format!(
                "scan start adapter={} timeout_ms={} name_prefix={:?} service_uuid={:?}",
                adapter.name(),
                timeout.as_millis(),
                name_prefix,
                service_uuid
            ),
        );

        adapter
            .set_discovery_filter(Self::discovery_filter(service_uuid))
            .await?;

        let mut devices = BTreeMap::<String, BleDevice>::new();
        let mut stream = adapter.discover_devices().await?;
        let deadline = Instant::now() + timeout;

        loop {
            let now = Instant::now();
            if now >= deadline {
                break;
            }

            let remaining = deadline.saturating_duration_since(now);
            match tokio::time::timeout(remaining, stream.next()).await {
                Ok(Some(AdapterEvent::DeviceAdded(address))) => {
                    if let Some(device) = self
                        .read_device(&adapter, address, name_prefix.as_deref(), service_uuid)
                        .await?
                    {
                        self.log.debug(TAG, &format!("scan device: {:?}", device));
                        devices.insert(device.address.clone(), device);
                    }
                }
                Ok(Some(_)) => {}
                Ok(None) => break,
                Err(_) => break,
            }
        }

        let devices: Vec<BleDevice> = devices.into_values().collect();
        self.log
            .info(TAG, &format!("scan finished devices={}", devices.len()));
        Ok(devices)
    }

    pub async fn device_list(&self) -> BleResult<Vec<BleDevice>> {
        let adapter = self.adapter().await?;
        let addresses = adapter.device_addresses().await?;
        let mut devices = Vec::with_capacity(addresses.len());

        for address in addresses {
            if let Some(device) = self.read_device(&adapter, address, None, None).await? {
                devices.push(device);
            }
        }

        self.log
            .debug(TAG, &format!("device list count={}", devices.len()));
        Ok(devices)
    }

    pub async fn device_connect(&self, params: DeviceParams) -> BleResult<BleDevice> {
        let adapter = self.adapter().await?;
        let device = self.device(&adapter, &params.address)?;

        if device.is_connected().await? {
            self.log
                .debug(TAG, &format!("already connected address={}", params.address));
        } else {
            self.connect_with_retry(&device, &params.address).await?;
        }

        self.await_services_resolved(&device, &params.address)
            .await?;

        let address = params.address.parse::<Address>()?;
        match self.read_device(&adapter, address, None, None).await? {
            Some(ble_device) => Ok(ble_device),
            None => Err(format!("device not found after connect: {}", params.address).into()),
        }
    }

    pub async fn device_disconnect(&self, params: DeviceParams) -> BleResult<()> {
        self.drop_subscriptions(&params.address);

        let adapter = self.adapter().await?;
        let device = self.device(&adapter, &params.address)?;
        if device.is_connected().await? {
            device.disconnect().await?;
        }
        self.log
            .info(TAG, &format!("disconnected address={}", params.address));
        Ok(())
    }

    pub async fn gatt_read(&self, params: GattParams) -> BleResult<GattValue> {
        let adapter = self.adapter().await?;
        let device = self.device(&adapter, &params.address)?;
        let characteristic = self
            .find_characteristic(&device, &params.service_uuid, &params.characteristic_uuid)
            .await?;

        let bytes = characteristic.read().await?;
        let (encoding, value) = encode_value(&bytes, params.encoding.as_deref())?;
        self.log.debug(
            TAG,
            &format!(
                "gatt read address={} characteristic={} bytes={}",
                params.address,
                params.characteristic_uuid,
                bytes.len()
            ),
        );
        Ok(GattValue {
            address: params.address,
            service_uuid: params.service_uuid,
            characteristic_uuid: params.characteristic_uuid,
            encoding,
            value,
        })
    }

    pub async fn gatt_write(&self, params: GattWriteParams) -> BleResult<()> {
        let adapter = self.adapter().await?;
        let device = self.device(&adapter, &params.address)?;
        let characteristic = self
            .find_characteristic(&device, &params.service_uuid, &params.characteristic_uuid)
            .await?;

        let bytes = decode_value(&params.value, params.encoding.as_deref())?;
        characteristic.write(&bytes).await?;
        self.log.debug(
            TAG,
            &format!(
                "gatt write address={} characteristic={} bytes={}",
                params.address,
                params.characteristic_uuid,
                bytes.len()
            ),
        );
        Ok(())
    }

    pub async fn gatt_subscribe(&self, params: GattParams) -> BleResult<()> {
        let adapter = self.adapter().await?;
        let device = self.device(&adapter, &params.address)?;
        let characteristic = self
            .find_characteristic(&device, &params.service_uuid, &params.characteristic_uuid)
            .await?;

        let mut stream = Box::pin(characteristic.notify().await?);
        let events = self.events.clone();
        let log = self.log.clone();
        let address = params.address.clone();
        let service_uuid = params.service_uuid.clone();
        let characteristic_uuid = params.characteristic_uuid.clone();
        let encoding = params.encoding.clone();

        // The task owns the characteristic (and with it the session), keeping
        // the notify stream alive until the device disconnects, the stream
        // ends, or an unsubscribe/disconnect aborts it.
        let handle = tokio::spawn(async move {
            let _characteristic = characteristic;
            while let Some(bytes) = stream.next().await {
                let (encoding, value) = match encode_value(&bytes, encoding.as_deref()) {
                    Ok(encoded) => encoded,
                    Err(error) => {
                        log.warning(TAG, &format!("notification encode failed: {error}"));
                        continue;
                    }
                };
                let payload = GattValue {
                    address: address.clone(),
                    service_uuid: service_uuid.clone(),
                    characteristic_uuid: characteristic_uuid.clone(),
                    encoding,
                    value,
                };
                let event = BleMqttEvent::new(
                    "gatt.notification",
                    serde_json::to_value(&payload).unwrap_or_default(),
                );
                if events.send(event).is_err() {
                    break;
                }
            }
            log.debug(
                TAG,
                &format!("notify stream ended address={address} characteristic={characteristic_uuid}"),
            );
        });

        let key = (params.address.clone(), params.characteristic_uuid.clone());
        if let Some(previous) = self
            .subscriptions
            .lock()
            .expect("subscriptions lock poisoned")
            .insert(key, handle)
        {
            previous.abort();
        }

        self.log.info(
            TAG,
            &format!(
                "subscribed address={} characteristic={}",
                params.address, params.characteristic_uuid
            ),
        );
        Ok(())
    }

    pub async fn gatt_unsubscribe(&self, params: GattParams) -> BleResult<()> {
        let key = (params.address.clone(), params.characteristic_uuid.clone());
        let removed = self
            .subscriptions
            .lock()
            .expect("subscriptions lock poisoned")
            .remove(&key);
        match removed {
            Some(handle) => {
                handle.abort();
                self.log.info(
                    TAG,
                    &format!(
                        "unsubscribed address={} characteristic={}",
                        params.address, params.characteristic_uuid
                    ),
                );
                Ok(())
            }
            None => Err(format!(
                "no subscription for address={} characteristic={}",
                params.address, params.characteristic_uuid
            )
            .into()),
        }
    }

    async fn adapter(&self) -> BleResult<Adapter> {
        let session = Session::new().await?;
        Ok(session.default_adapter().await?)
    }

    fn device(&self, adapter: &Adapter, address: &str) -> BleResult<Device> {
        let address = address.parse::<Address>()?;
        Ok(adapter.device(address)?)
    }

    // BlueZ LE connects abort spuriously (le-connection-abort-by-local); a few
    // attempts is the standard workaround.
    async fn connect_with_retry(&self, device: &Device, address: &str) -> BleResult<()> {
        let mut last_error: Option<Box<dyn std::error::Error + Send + Sync>> = None;
        for attempt in 1..=CONNECT_ATTEMPTS {
            self.log.info(
                TAG,
                &format!("connecting address={address} attempt={attempt}/{CONNECT_ATTEMPTS}"),
            );
            match device.connect().await {
                Ok(()) => return Ok(()),
                Err(error) => {
                    self.log.warning(
                        TAG,
                        &format!("connect attempt {attempt} failed address={address}: {error}"),
                    );
                    last_error = Some(error.into());
                }
            }
        }
        Err(last_error.unwrap_or_else(|| format!("connect failed: {address}").into()))
    }

    // The first gatt.read must not race BlueZ service discovery.
    async fn await_services_resolved(&self, device: &Device, address: &str) -> BleResult<()> {
        let deadline = Instant::now() + SERVICES_RESOLVED_TIMEOUT;
        while Instant::now() < deadline {
            if device.is_services_resolved().await? {
                return Ok(());
            }
            tokio::time::sleep(SERVICES_RESOLVED_POLL).await;
        }
        Err(format!("services not resolved in time: {address}").into())
    }

    async fn find_characteristic(
        &self,
        device: &Device,
        service_uuid: &str,
        characteristic_uuid: &str,
    ) -> BleResult<Characteristic> {
        let service_uuid = service_uuid.parse::<Uuid>()?;
        let characteristic_uuid = characteristic_uuid.parse::<Uuid>()?;

        for service in device.services().await? {
            if service.uuid().await? != service_uuid {
                continue;
            }
            for characteristic in service.characteristics().await? {
                if characteristic.uuid().await? == characteristic_uuid {
                    return Ok(characteristic);
                }
            }
            return Err(format!(
                "characteristic {characteristic_uuid} not found in service {service_uuid}"
            )
            .into());
        }
        Err(format!("service {service_uuid} not found on device").into())
    }

    fn drop_subscriptions(&self, address: &str) {
        let mut subscriptions = self
            .subscriptions
            .lock()
            .expect("subscriptions lock poisoned");
        subscriptions.retain(|(subscribed_address, _), handle| {
            if subscribed_address == address {
                handle.abort();
                false
            } else {
                true
            }
        });
    }

    async fn default_adapter_if_available(&self, session: &Session) -> BleResult<Option<Adapter>> {
        if session.adapter_names().await?.is_empty() {
            return Ok(None);
        }
        Ok(Some(session.default_adapter().await?))
    }

    async fn adapter_status_from_adapter(&self, adapter: &Adapter) -> BleResult<AdapterStatus> {
        Ok(AdapterStatus {
            available: true,
            name: Some(adapter.name().to_string()),
            powered: adapter.is_powered().await?,
            discovering: adapter.is_discovering().await?,
        })
    }

    fn discovery_filter(service_uuid: Option<Uuid>) -> DiscoveryFilter {
        let mut uuids = HashSet::new();
        if let Some(uuid) = service_uuid {
            uuids.insert(uuid);
        }

        DiscoveryFilter {
            uuids,
            transport: DiscoveryTransport::Le,
            ..Default::default()
        }
    }

    async fn read_device(
        &self,
        adapter: &Adapter,
        address: Address,
        name_prefix: Option<&str>,
        service_uuid: Option<Uuid>,
    ) -> BleResult<Option<BleDevice>> {
        let device = adapter.device(address)?;
        let name = device.name().await.unwrap_or(None);
        let service_uuids_set = device.uuids().await.unwrap_or(None).unwrap_or_default();

        if let Some(prefix) = name_prefix {
            if !name.as_deref().unwrap_or_default().starts_with(prefix) {
                return Ok(None);
            }
        }

        if let Some(uuid) = service_uuid {
            if !service_uuids_set.contains(&uuid) {
                return Ok(None);
            }
        }

        let mut service_uuids: Vec<String> = service_uuids_set
            .into_iter()
            .map(|uuid| uuid.to_string())
            .collect();
        service_uuids.sort();

        Ok(Some(BleDevice {
            address: address.to_string(),
            name,
            rssi: device.rssi().await.unwrap_or(None),
            service_uuids,
            connected: device.is_connected().await.unwrap_or(false),
            paired: device.is_paired().await.unwrap_or(false),
            trusted: device.is_trusted().await.unwrap_or(false),
        }))
    }
}
