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
# Note on Java: the Linux and macOS installs are jpackage app-images, which
# bundle their own Java runtime. The Windows portable install is a plain
# Gradle distribution zip and does NOT bundle a runtime - it needs a
# separately installed Java 21+. On Windows, this script detects the java
# on THIS shell's PATH/JAVA_HOME (e.g. one managed by SDKMAN, which is not
# visible from a plain cmd.exe/Explorer launch) and bakes it into a small
# launcher wrapper, so the desktop/Start Menu shortcuts work out of the box.
# If no java can be detected here, a warning is printed instead.
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

# Resolves a JAVA_HOME from this shell's own environment (JAVA_HOME or the
# 'java' on PATH), so it can be baked into the generated launcher wrapper.
# Prints the resolved path and returns 0, or returns 1 if none was found.
detect_windows_java_home() {
  if [ -n "${JAVA_HOME:-}" ] && [ -f "${JAVA_HOME}/bin/java.exe" ]; then
    printf '%s' "${JAVA_HOME}"
    return 0
  fi

  local java_bin
  java_bin=$(command -v java.exe 2>/dev/null || command -v java 2>/dev/null || true)
  if [ -z "${java_bin}" ]; then
    return 1
  fi
  if command -v readlink >/dev/null 2>&1; then
    java_bin=$(readlink -f "${java_bin}" 2>/dev/null || printf '%s' "${java_bin}")
  fi
  printf '%s' "$(dirname "$(dirname "${java_bin}")")"
}

# Writes a wrapper .bat that sets JAVA_HOME (if not already defined by the
# environment it runs in) before calling the Gradle-generated launcher.
# This is what shortcuts should target, since the Gradle-generated
# AlipsaAccounting.bat is regenerated on every build and only auto-detects
# Java that is visible from wherever it happens to be launched.
create_windows_launcher_wrapper() {
  local app_root="$1"
  local java_home="$2"
  local wrapper="${app_root}/bin/Launch${APP_NAME}.bat"
  local java_home_win=""

  if [ -n "${java_home}" ]; then
    java_home_win=$(cygpath -w "${java_home}")
  fi

  {
    printf '@echo off\r\n'
    if [ -n "${java_home_win}" ]; then
      printf 'if not defined JAVA_HOME set "JAVA_HOME=%s"\r\n' "${java_home_win}"
    fi
    printf 'call "%%~dp0%s.bat" %%*\r\n' "${APP_NAME}"
  } > "${wrapper}"

  printf '%s' "${wrapper}"
}

create_windows_shortcuts() {
  local app_root="$1"
  local launcher="$2"
  local launcher_win workdir_win icon_src icon_dst icon_win icon_line ps1

  if ! command -v powershell.exe >/dev/null 2>&1; then
    echo "  Warning: powershell.exe not found; skipping shortcut creation." >&2
    return
  fi

  icon_src="${SCRIPT_DIR}/packaging/windows/${APP_NAME}.ico"
  icon_win=""
  if [ -f "${icon_src}" ]; then
    icon_dst="${app_root}/${APP_NAME}.ico"
    cp "${icon_src}" "${icon_dst}"
    icon_win=$(cygpath -w "${icon_dst}")
  fi

  launcher_win=$(cygpath -w "${launcher}")
  workdir_win=$(cygpath -w "${app_root}/bin")

  icon_line=""
  if [ -n "${icon_win}" ]; then
    icon_line="\$s.IconLocation = '${icon_win}'"
  fi

  ps1=$(mktemp "${TMPDIR:-/tmp}/alipsa-shortcut-XXXXXX")
  mv "${ps1}" "${ps1}.ps1"
  ps1="${ps1}.ps1"

  cat > "${ps1}" <<EOF
\$ErrorActionPreference = 'Stop'
\$WshShell = New-Object -ComObject WScript.Shell

function New-AppShortcut(\$path) {
  \$s = \$WshShell.CreateShortcut(\$path)
  \$s.TargetPath = '${launcher_win}'
  \$s.WorkingDirectory = '${workdir_win}'
  ${icon_line}
  \$s.Save()
}

\$desktop = [Environment]::GetFolderPath('Desktop')
New-AppShortcut (Join-Path \$desktop '${APP_NAME}.lnk')

\$programs = [Environment]::GetFolderPath('Programs')
New-AppShortcut (Join-Path \$programs '${APP_NAME}.lnk')
EOF

  echo "  Creating desktop and Start Menu shortcuts..."
  if powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "${ps1}")"; then
    echo "  Shortcuts created."
  else
    echo "  Warning: failed to create shortcuts." >&2
  fi
  rm -f "${ps1}"
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

  local generated_launcher="${app_root}/bin/${APP_NAME}.bat"
  if [ ! -f "${generated_launcher}" ]; then
    echo "Error: launcher not found: ${generated_launcher}" >&2
    exit 1
  fi

  DETECTED_JAVA_HOME=""
  if DETECTED_JAVA_HOME=$(detect_windows_java_home); then
    echo "  Detected Java runtime: ${DETECTED_JAVA_HOME}"
  fi

  LAUNCHER=$(create_windows_launcher_wrapper "${app_root}" "${DETECTED_JAVA_HOME}")

  create_windows_shortcuts "${app_root}" "${LAUNCHER}"

  if [ -z "${DETECTED_JAVA_HOME}" ]; then
    echo "" >&2
    echo "Warning: no Java installation was detected (JAVA_HOME is not set and 'java' is not on PATH)." >&2
    echo "  ${APP_NAME} requires Java 21 or later; this portable install does not bundle a runtime." >&2
    echo "  Install a JDK/JRE 21+ and set JAVA_HOME (or add java to PATH) before running the launcher." >&2
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
if [ "${PLATFORM}" = "windows" ]; then
  echo "  Shortcuts: Desktop and Start Menu"
  if [ -n "${DETECTED_JAVA_HOME}" ]; then
    echo "  Java runtime: ${DETECTED_JAVA_HOME} (baked into the launcher wrapper)"
  else
    echo "  Requires: Java 21+ (JAVA_HOME or java on PATH) - not bundled with this portable install"
  fi
fi
echo ""
echo "Start the app from the applications menu or run: ${LAUNCHER}"
