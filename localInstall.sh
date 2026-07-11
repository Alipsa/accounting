#!/usr/bin/env bash
#
# localInstall.sh
#
# Builds the current platform release and installs/updates the local app image
# without creating a formal GitHub release. Intended for developers who want to
# run the locally built version as their daily driver.
#
# Usage:
#   ./localInstall.sh                # build and install to default location
#   ./localInstall.sh --no-build     # skip build, install latest local package
#   ./localInstall.sh --dir <path>   # parent directory for the installation
#
# Default install directory:
#   ~/.local/lib/alipsa-accounting
#
# The release zip is extracted into that directory, producing:
#   <dir>/AlipsaAccounting/   (app image)
#   <dir>/install.sh          (desktop entry registration)
#   <dir>/uninstall.sh        (removal helper)
#   <dir>/skill/              (MCP skill documentation)
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_NAME="AlipsaAccounting"
PACKAGE_NAME="alipsa-accounting"
BUILD=true
INSTALL_DIR="${INSTALL_DIR:-${HOME}/.local/lib/${PACKAGE_NAME}}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --no-build)
      BUILD=false
      shift
      ;;
    --dir)
      if [ -z "${2:-}" ]; then
        echo "Error: --dir requires a path argument." >&2
        exit 1
      fi
      INSTALL_DIR="$2"
      shift 2
      ;;
    --help|-h)
      sed -n '2,22p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Usage: $0 [--no-build] [--dir <path>]" >&2
      exit 1
      ;;
  esac
done

if [ "${BUILD}" = true ]; then
  echo "Building current platform release..."
  cd "${SCRIPT_DIR}"
  ./gradlew :app:packageCurrentPlatformRelease
fi

VERSION=$(cd "${SCRIPT_DIR}" && ./gradlew :app:properties -q 2>/dev/null | grep "^version:" | awk '{print $2}')
if [ -z "${VERSION}" ]; then
  echo "Error: could not determine project version." >&2
  exit 1
fi

case "$(uname -s)" in
  Linux*)   PLATFORM=linux ;;
  Darwin*)  PLATFORM=macos ;;
  CYGWIN*|MINGW*|MSYS*) PLATFORM=windows ;;
  *)
    echo "Error: unsupported platform: $(uname -s)" >&2
    exit 1
    ;;
esac

if [ "${PLATFORM}" != "linux" ]; then
  echo "Warning: localInstall.sh is currently optimized for Linux. Continuing anyway..." >&2
fi

ZIP_FILE="${SCRIPT_DIR}/app/build/release/${PLATFORM}/${PACKAGE_NAME}-${VERSION}-${PLATFORM}.zip"
if [ ! -f "${ZIP_FILE}" ]; then
  echo "Error: release package not found: ${ZIP_FILE}" >&2
  echo "Run $0 without --no-build to build it first." >&2
  exit 1
fi

echo "Installing ${APP_NAME} ${VERSION} under ${INSTALL_DIR}..."

mkdir -p "${INSTALL_DIR}"

# Clean up the app image and scripts from a previous install, but leave any
# sibling files/directories that the user may have added manually.
for entry in "${APP_NAME}" install.sh uninstall.sh skill; do
  if [ -e "${INSTALL_DIR}/${entry}" ]; then
    rm -rf "${INSTALL_DIR}/${entry}"
  fi
done

echo "  Extracting ${ZIP_FILE}..."
unzip -q "${ZIP_FILE}" -d "${INSTALL_DIR}"

APP_DIR="${INSTALL_DIR}/${APP_NAME}"
LAUNCHER="${APP_DIR}/bin/${APP_NAME}"
if [ ! -f "${LAUNCHER}" ]; then
  echo "Error: launcher not found: ${LAUNCHER}" >&2
  exit 1
fi
chmod +x "${LAUNCHER}"

# Run the bundled install script to register the desktop entry on Linux.
if [ "${PLATFORM}" = "linux" ]; then
  INSTALL_SCRIPT="${INSTALL_DIR}/install.sh"
  if [ -x "${INSTALL_SCRIPT}" ]; then
    echo "  Registering desktop entry..."
    (cd "${INSTALL_DIR}" && ./install.sh)
  else
    echo "Warning: install.sh not found or not executable; skipping desktop entry registration." >&2
  fi
fi

echo ""
echo "Installed ${APP_NAME} ${VERSION}."
echo "  App dir:  ${APP_DIR}"
echo "  Launcher: ${LAUNCHER}"
echo ""
echo "Start from the applications menu or run: ${LAUNCHER}"
