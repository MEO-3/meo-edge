# Repository Guidelines

## Project Structure & Module Organization

This repository is the MEO 3 edge service (the "hub") for IoT education hardware; it runs on the gateway hardware (Raspberry Pi / Nano Pi class).

Terms: **gateway** = the hardware box; **edge** = the gateway plus the services running on it (this repo, the Rust BLE service, the MQTT broker). It is a single Gradle project (`meo-edge`), not a multi-module build. Service code lives under `src/main/java/org/thingai/app/meo`:

- `Main.java` / `MeoService.java` — entry point; starts a Javalin HTTP server on `MEO_SERVICE_PORT` (default `7070`).
- `api/` — Javalin route registration. Keep routes thin and delegate to handlers.
- `handler/` — business logic, one subpackage per concern: `mngt/` (`MeoMngtHandler`, device registry), `provision/` (`MeoProvisionHandler`, `ProvisionBleUuid`), `msg/` (`MeoMsgHandler`), `cloud/` (`MeoCloudHandler`). Callback interfaces live in `callback/`.
- `blemqtt/` — MQTT client (`BlemqttClient`) that drives the Rust BLE service over the generic `blemqtt` protocol.
- `define/` — enums and shared constants (`MeoErr`, `MeoMsgEdgeFrame`, `MeoMsgCloudFrame`, `MeoTopic`, `MeoDevProvisionStatus`, `MeoDevTransportType`).
- `api/dto/` — HTTP request/response shapes (`DeviceResponse`, `CommandRequest`, ...).
- `entity/` — DTO/entity classes (`MeoDevice`, `MeoDeviceProvision`, ...).
- `util/` — helpers (`ByteUtil`, `JsonUtil`, `NetUtil`).

The base framework (`Service`, `Dao`/`DaoSqlite`, `ILog`; packages `org.thingai.base.*` and `org.thingai.platform.*`) is **not in source** — it ships as bundled jars in `libs/` (`applicationbase.jar`, `edgeplatform.jar`, `aibase.jar`). Read the jars or treat them as external API.

The Rust BLE service lives under `rust/meo-helper` (BlueZ/BLE only; owns the BLE hardware and exposes `blemqtt` over MQTT). Developer notes and authoritative contracts are in `docs/` (`project_specs.md`, `firmware_development_guide.md`). Persistence is SQLite via `DaoSqlite` at `MEO_DATA_DIR/meo.db`. Node-RED is **not** vendored here, and the MQTT broker (Mosquitto) is a system dependency.

## Build, Test, and Development Commands

A top-level `Makefile` wraps Gradle and the Rust build. The Gradle wrapper may not be executable in a fresh checkout; use `bash ./gradlew` if `./gradlew` is denied. Use **JDK 17 or 21** — very new JDKs can fail before compilation (including running Gradle itself).

- `make build` — `gradlew installDist` → `build/install/meo-edge`.
- `make compile` — compile only (`gradlew compileJava`).
- `make test` — run Java tests (JUnit 5).
- `make clean` — remove Gradle build output.
- `make ble-x86` / `make ble-arm` — build the Rust BLE binary (host x86_64 / cross aarch64).
- `make package` / `make dist` — per-arch release tarballs (`build/dist/meo-edge-<arch>.tar.gz`); the matching BLE binary must be built first.

Runtime config: environment variables `MEO_SERVICE_PORT` and `MEO_DATA_DIR` (see `config/meo.env`). Run the installDist launcher at `build/install/meo-edge/bin/meo-edge`.

## Coding Style & Naming Conventions

Java with 4-space indentation. Keep package names under `org.thingai.app.meo`. Follow existing class prefixes such as `Meo...` (`MeoService`, `MeoMngtHandler`) and `Blemqtt...` (`BlemqttClient`, `BlemqttCommand`). API route classes stay thin and delegate business logic to `handler/` classes.

Naming:

- `Meo` prefix — contracts shared across layers (device, edge, cloud): `MeoErr`, `MeoTopic`, `MeoMsg*Frame`, handlers, entities.
- `MeoDev*` — constants describing a device (`MeoDevProvisionStatus`, `MeoDevTransportType`).
- `MeoMsgEdgeFrame` / `MeoMsgCloudFrame` — wire frames per hop: device ↔ edge, edge ↔ cloud.
- No prefix — edge-internal only: `api/dto/` classes, `ProvisionBleUuid`.
- `MeoErr` codes are ranged by where the error occurred: 0 generic, 1–99 device, 100–199 edge, 200–299 cloud.

Prefer explicit DTO/entity classes over raw JSON maps for stable service contracts. Keep comments short and useful, especially around hardware/BLE protocol details.

## Testing Guidelines

JUnit 5 is configured (`MeoFrameTest` covers frame encoding). Add tests under `src/test/java`, mirroring the production package. Name tests `*Test.java`, for example `MeoMngtHandlerTest.java`. Focus coverage on `blemqtt` command/reply handling, the provisioning flow, protocol parsing, and DAO-backed behavior.

## Commit & Pull Request Guidelines

History uses Conventional Commit-style messages, for example `feat: add cors rule javalin` and `refactor: drop vendored Node-RED and shell scripts`. Keep commits small and scoped.

Pull requests should include a short purpose statement, implementation notes, test results, and linked issues when relevant. For BLE/MQTT changes, document topic names (`blemqtt/v1/command`, `blemqtt/v1/reply/+`, `blemqtt/v1/event`), payload examples, and any required Node-RED, Mosquitto, or Avahi setup.

## Security & Configuration Tips

Do not commit local database files, credentials, tokens, or generated device keys. Treat MQTT broker URLs, Wi-Fi details, and device keys as runtime configuration, not source constants.
