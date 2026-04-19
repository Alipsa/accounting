#!/usr/bin/env bash
# Checks for dependency updates using the Ben Manes versions plugin.
# --no-configuration-cache and --no-parallel are required by the plugin.
set -euo pipefail
./gradlew :app:dependencyUpdates --no-configuration-cache --no-parallel
