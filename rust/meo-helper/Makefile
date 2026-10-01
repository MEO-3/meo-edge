BIN := meo-helper
TARGET ?= aarch64-unknown-linux-gnu
CROSS ?= cross
CARGO ?= cargo

.PHONY: help build build-amd64 build-arm64 build-aarch64 check check-arm64 test clean run print-artifact

help:
	@echo "Targets:"
	@echo "  make build          Build for the host with cargo"
	@echo "  make build-amd64    Build release binary for x86_64 with cross (for shipping)"
	@echo "  make build-arm64    Build release binary for ARM64/aarch64 with cross"
	@echo "  make build-aarch64  Alias for build-arm64"
	@echo "  make check          Check for the host with cargo"
	@echo "  make check-arm64    Check ARM64/aarch64 target with cross"
	@echo "  make test           Run host tests with cargo"
	@echo "  make clean          Remove Cargo build output"
	@echo ""
	@echo "Variables:"
	@echo "  TARGET=$(TARGET)"
	@echo "  CROSS=$(CROSS)"

build:
	$(CARGO) build

# For packaging, not for dev: a host `cargo build` on a modern distro links a
# glibc newer than Debian/Raspberry Pi OS stable provides, so the binary will
# not start there. `cross` builds against an older glibc floor.
build-amd64:
	$(CROSS) build --target x86_64-unknown-linux-gnu --release
	@$(MAKE) print-artifact TARGET=x86_64-unknown-linux-gnu

build-arm64:
	$(CROSS) build --target $(TARGET) --release
	@$(MAKE) print-artifact TARGET=$(TARGET)

build-aarch64: build-arm64

check:
	$(CARGO) check

check-arm64:
	$(CROSS) check --target $(TARGET)

test:
	$(CARGO) test

clean:
	$(CARGO) clean

run:
	$(CARGO) run

print-artifact:
	@echo "Built artifact: target/$(TARGET)/release/$(BIN)"
