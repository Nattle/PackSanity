# Developer entry points for the native library and full loading integrations.

.DEFAULT_GOAL := build

BUILD_DIR ?= build/meson
MESON ?= meson
MESON_ARGS ?=
MESON_BUILD_ARGS ?=
INSTALL_ARGS ?=
PACKSANITY_PYTHON ?= python3
PYTHON_NATIVE_BUILD_DIR ?= build/python-native-meson
WORLD ?=
WORLD_CHECK_ARGS ?=
PACK ?=
PACK_CHECK_ARGS ?=

.PHONY: all build configure test check pack-check world-check python-native install clean help

all: build

configure:
	$(MESON) setup --reconfigure "$(BUILD_DIR)" . $(MESON_ARGS)

build: configure
	$(MESON) compile -C "$(BUILD_DIR)" $(MESON_BUILD_ARGS)

test: build
	$(MESON) test -C "$(BUILD_DIR)" --print-errorlogs

check: test

# Example: make pack-check PACK=./my-pack.zip
# Extra runner flags can be passed with PACK_CHECK_ARGS='--variant standard --json'.
pack-check:
	@test -n "$(PACK)" || { echo "usage: make pack-check PACK=<pack-directory-or-zip>" >&2; exit 2; }
	./test-pack.sh "$(PACK)" $(PACK_CHECK_ARGS)

world-check:
	@test -n "$(WORLD)" || { echo "usage: make world-check WORLD=<world.apworld>" >&2; exit 2; }
	./test-world.sh "$(WORLD)" $(WORLD_CHECK_ARGS)

python-native:
	@python="$$("$(PACKSANITY_PYTHON)" python/tools/prepare_runtime.py)" && \
	  $(MESON) setup --reconfigure "$(PYTHON_NATIVE_BUILD_DIR)" . -Dpython_bridge=true "-Dpython=$$python" && \
	  $(MESON) compile -C "$(PYTHON_NATIVE_BUILD_DIR)" $(MESON_BUILD_ARGS)

install: build
	$(MESON) install -C "$(BUILD_DIR)" $(INSTALL_ARGS)

clean:
	$(MESON) compile -C "$(BUILD_DIR)" --clean

help:
	@echo "make test                    Build and run native regressions"
	@echo "make python-native           Build the C bridge against local pinned CPython"
	@echo "make pack-check PACK=<pack>   Run the full PopTracker loading integration"
	@echo "make world-check WORLD=<world.apworld>   Reconstruct an APWorld"
	@echo "Pass scenario flags in PACK_CHECK_ARGS or WORLD_CHECK_ARGS."
	@echo "See docs/testing.md for --fixture, --json, --strict and state formats."
