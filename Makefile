# Makefile for the MEO Edge.
# Single entry point for building the Java service, the Rust BLE binary, and
# the per-architecture release packages. Packaging itself is implemented as
# Gradle tasks (see build.gradle.kts); this Makefile just wires the steps.

GRADLE   ?= bash ./gradlew
CARGO    ?= cargo
BLE_DIR  := rust/meo-helper

.PHONY: help compile test build clean \
        ble-x86 ble-arm package package-x86 package-arm dist

help:
	@echo "Targets:"
	@echo "  make build        Compile + stage the Java service (gradlew installDist)"
	@echo "  make compile      Compile the Java service (gradlew compileJava)"
	@echo "  make test         Run Java tests (gradlew test)"
	@echo "  make clean        Remove Gradle build output"
	@echo ""
	@echo "  make ble-x86      Build the Rust BLE binary for x86_64 (release, host)"
	@echo "  make ble-x86-dist Build the x86_64 BLE binary for shipping (cross, old glibc)"
	@echo "  make ble-arm      Build the Rust BLE binary for ARM64 (release, cross)"
	@echo ""
	@echo "  make package-x86  Package the linux-x86_64 distribution (Java + BLE)"
	@echo "  make package-arm  Package the linux-arm64 distribution (Java + BLE)"
	@echo "  make package      Package both distributions"
	@echo "  make dist         Build everything (Java + both BLE binaries) and package"
	@echo ""
	@echo "Output: build/dist/meo-edge-<arch>.tar.gz"

# --- Java service -----------------------------------------------------------

compile:
	$(GRADLE) compileJava

test:
	$(GRADLE) test

build:
	$(GRADLE) installDist

clean:
	$(GRADLE) clean

# --- Rust BLE binary --------------------------------------------------------

ble-x86:
	cd $(BLE_DIR) && $(CARGO) build --release

# Use this, not ble-x86, for anything shipped: a host build links a glibc newer
# than Debian/Raspberry Pi OS stable has.
ble-x86-dist:
	$(MAKE) -C $(BLE_DIR) build-amd64

ble-arm:
	$(MAKE) -C $(BLE_DIR) build-arm64

# --- Packaging (Java service + BLE binary, no Node-RED) ---------------------

package-x86:
	$(GRADLE) package-linux-x86_64

package-arm:
	$(GRADLE) package-linux-arm64

package:
	$(GRADLE) packageAll

# Full release flow: Java + both BLE binaries, then both packages.
dist: ble-x86 ble-arm package
