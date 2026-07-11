#!/usr/bin/env bash
#
# localInstall.sh
#
# Builds the current platform release and installs/updates the local app image
# without creating a formal GitHub release. Intended for developers who want to
# run the locally built version as their daily driver.
#
# Supported platforms:
#   - Linux   (full support, including desktop entry registration)
#   - macOS   (installs the .app bundle)
#   - Windows (portable install from the Gradle distribution zip)
#
# Usage:
#   ./localInstall.sh                # build and install to default location
#   ./localInstall.sh --no-build     # skip build, install latest local package
#   ./localInstall.sh --dir <path>   # parent directory for the installation
#
# Default install directory:
#   Linux   : ~/.local/lib/alipsa-accounting
#   macOS   : ~/Applications
#   Windows : %LOCALAPPDATA%
#
# The release zip is extracted into that directory, producing:
#   Linux : <dir>/AlipsaAccounting/   (jpackage app image)
#   macOS : <dir>/AlipsaAccounting.app/
#   Windows : <dir>/AlipsaAccounting/  (bin/, lib/, skill/)
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_NAME="AlipsaAccounting"
PACKAGE_NAME="alipsa-accounting"
BUILD=true
INSTALL_DIR=""

detect_platform() {
  case "$(uname -s)" in
    Linux*)   echo linux ;;
    Darwin*)  echo macos ;;
    CYGWIN*|MINGW*|MSYS*) echo windows ;;
    *)        echo unsupported ;;
  esac
}

PLATFORM=$(detect_platform)
if [ "${PLATFORM}" = "unsupported" ]; then
  echo "Error: unsupported platform: $(uname -s)" >&2
  exit 1
fi

zip_release_dir() {
  case "$1" in
    linux)    echo release/linux ;;
    macos)    echo release/macos-release ;;
  esac
}

default_install_dir() {
  case "${PLATFORM}" in
    linux) echo "${HOME}/.local/lib/${PACKAGE_NAME}" ;;
    macos) echo "${HOME}/Applications" ;;
    windows) echo "${LOCALAPPDATA:-${HOME}/AppData/Local}" ;;
  esac
}

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
      sed -n '2,28p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Usage: $0 [--no-build] [--dir <path>]" >&2
      exit 1
      ;;
  esac
done

INSTALL_DIR="${INSTALL_DIR:-$(default_install_dir)}"

if [ "${BUILD}" = true ]; then
  echo "Building current platform release..."
  cd "${SCRIPT_DIR}"
  case "${PLATFORM}" in
    windows)
      ./gradlew :app:distZip
      ;;
    *)
      ./gradlew :app:packageCurrentPlatformRelease
      ;;
  esac
fi

VERSION=$(cd "${SCRIPT_DIR}" && ./gradlew :app:properties -q 2>/dev/null | grep "^version:" | awk '{print $2}')
if [ -z "${VERSION}" ]; then
  echo "Error: could not determine project version." >&2
  exit 1
fi

install_linux_macos() {
  local zip_file="${SCRIPT_DIR}/app/build/$(zip_release_dir "${PLATFORM}")/${PACKAGE_NAME}-${VERSION}-${PLATFORM}.zip"
  if [ ! -f "${zip_file}" ]; then
    echo "Error: release package not found: ${zip_file}" >&2
    echo "Run $0 without --no-build to build it first." >&2
    exit 1
  fi

  echo "Installing ${APP_NAME} ${VERSION} under ${INSTALL_DIR}..."

  mkdir -p "${INSTALL_DIR}"

  # Clean up a previous install of the same bundle.
  case "${PLATFORM}" in
    linux)
      for entry in "${APP_NAME}" install.sh uninstall.sh skill; do
        if [ -e "${INSTALL_DIR}/${entry}" ]; then
          rm -rf "${INSTALL_DIR}/${entry}"
        fi
      done
      ;;
    macos)
      if [ -e "${INSTALL_DIR}/${APP_NAME}.app" ]; then
        rm -rf "${INSTALL_DIR}/${APP_NAME}.app"
      fi
      ;;
  esac

  echo "  Extracting ${zip_file}..."
  unzip -oq "${zip_file}" -d "${INSTALL_DIR}"

  case "${PLATFORM}" in
    linux)
      LAUNCHER="${INSTALL_DIR}/${APP_NAME}/bin/${APP_NAME}"
      if [ ! -f "${LAUNCHER}" ]; then
        echo "Error: launcher not found: ${LAUNCHER}" >&2
        exit 1
      fi
      chmod +x "${LAUNCHER}"

      INSTALL_SCRIPT="${INSTALL_DIR}/install.sh"
      if [ -x "${INSTALL_SCRIPT}" ]; then
        echo "  Registering desktop entry..."
        (cd "${INSTALL_DIR}" && ./install.sh)
      fi
      ;;
    macos)
      APP_BUNDLE="${INSTALL_DIR}/${APP_NAME}.app"
      LAUNCHER="${APP_BUNDLE}/Contents/MacOS/${APP_NAME}"
      if [ ! -d "${APP_BUNDLE}" ] || [ ! -f "${LAUNCHER}" ]; then
        echo "Error: app bundle not found: ${APP_BUNDLE}" >&2
        exit 1
      fi
      chmod +x "${LAUNCHER}"
      ;;
  esac
}

install_windows() {
  local zip_file="${SCRIPT_DIR}/app/build/distributions/app-${VERSION}.zip"
  if [ ! -f "${zip_file}" ]; then
    echo "Error: distribution zip not found: ${zip_file}" >&2
    echo "Run $0 without --no-build to build it first." >&2
    exit 1
  fi

  local app_root="${INSTALL_DIR}/${APP_NAME}"
  echo "Installing ${APP_NAME} ${VERSION} under ${app_root}..."

  if [ -d "${app_root}" ]; then
    rm -rf "${app_root}"
  fi
  mkdir -p "${app_root}"

  local staging_dir
  staging_dir=$(mktemp -d "${TMPDIR:-/tmp}/alipsa-install-XXXXXX")
  trap 'rm -rf "${staging_dir}"' EXIT

  echo "  Extracting ${zip_file}..."
  unzip -oq "${zip_file}" -d "${staging_dir}"

  local extracted_dir="${staging_dir}/app-${VERSION}"
  if [ ! -d "${extracted_dir}" ]; then
    echo "Error: expected top-level directory app-${VERSION} not found in ${zip_file}" >&2
    exit 1
  fi

  echo "  Moving files into place..."
  mv "${extracted_dir}/"* "${app_root}/"

  LAUNCHER="${app_root}/bin/${APP_NAME}.bat"
  if [ ! -f "${LAUNCHER}" ]; then
    echo "Error: launcher not found: ${LAUNCHER}" >&2
    exit 1
  fi
}

case "${PLATFORM}" in
  windows)
    install_windows
    ;;
  *)
    install_linux_macos
    ;;
esac

echo ""
echo "Installed ${APP_NAME} ${VERSION}."
echo "  Launcher: ${LAUNCHER}"
echo ""
echo "Start the app from the applications menu or run: ${LAUNCHER}"
