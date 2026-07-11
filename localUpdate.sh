#!/usr/bin/env bash
#
# localUpdate.sh
#
# Updates an existing Alipsa Accounting installation to the locally built
# version without creating a formal release.
#
# Usage:
#   ./localUpdate.sh                # auto-discover and update
#   ./localUpdate.sh --no-build     # skip build, use latest local package
#   ./localUpdate.sh --dir <path>   # update install under <path>
#
# Discovery order:
#   1. --dir / INSTALL_DIR
#   2. platform-specific shortcuts / launchers
#   3. common install locations
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_NAME="AlipsaAccounting"
PACKAGE_NAME="alipsa-accounting"
BUILD=true
INSTALL_DIR="${INSTALL_DIR:-}"

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
      sed -n '2,18p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Usage: $0 [--no-build] [--dir <path>]" >&2
      exit 1
      ;;
  esac
done

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
    windows)  echo release/windows-release ;;
  esac
}

# Returns the parent directory of an existing app image, or empty string.
find_existing_install() {
  local candidate

  # 1. Explicit override.
  if [ -n "${INSTALL_DIR}" ]; then
    if is_valid_install "${INSTALL_DIR}"; then
      echo "${INSTALL_DIR}"
      return
    fi
    echo "Error: --dir points to a directory that does not contain an existing ${APP_NAME} installation." >&2
    exit 1
  fi

  # 2. Shortcut / launcher lookup.
  candidate=$(find_via_shortcut)
  if [ -n "${candidate}" ]; then
    echo "${candidate}"
    return
  fi

  # 3. Common install locations.
  candidate=$(find_via_common_locations)
  if [ -n "${candidate}" ]; then
    echo "${candidate}"
    return
  fi

  # Not found.
  echo ""
}

is_valid_install() {
  local dir="$1"
  case "${PLATFORM}" in
    linux|windows)
      [ -d "${dir}/${APP_NAME}/bin" ]
      ;;
    macos)
      [ -d "${dir}/${APP_NAME}.app" ]
      ;;
  esac
}

find_via_shortcut() {
  case "${PLATFORM}" in
    linux)
      find_via_linux_desktop_file
      ;;
    macos)
      find_via_macos_app_bundle
      ;;
    windows)
      find_via_windows_shortcut
      ;;
  esac
}

find_via_linux_desktop_file() {
  local desktop_file="${XDG_DATA_HOME:-${HOME}/.local/share}/applications/${APP_NAME}.desktop"
  if [ ! -f "${desktop_file}" ]; then
    return
  fi

  local exec_path
  exec_path=$(grep "^Exec=" "${desktop_file}" | head -n 1 | sed 's/^Exec=//')
  if [ -z "${exec_path}" ]; then
    return
  fi

  # Exec path is expected to be <parent>/AlipsaAccounting/bin/AlipsaAccounting
  local bin_dir
  bin_dir="$(dirname "${exec_path}")"
  local app_dir
  app_dir="$(dirname "${bin_dir}")"
  local parent_dir
  parent_dir="$(dirname "${app_dir}")"

  if is_valid_install "${parent_dir}"; then
    echo "${parent_dir}"
  fi
}

find_via_macos_app_bundle() {
  local candidates=(
    "/Applications/${APP_NAME}"
    "${HOME}/Applications/${APP_NAME}"
  )
  for candidate in "${candidates[@]}"; do
    if is_valid_install "${candidate}"; then
      echo "${candidate}"
      return
    fi
  done
}

find_via_windows_shortcut() {
  local lnk_path
  lnk_path=$(find_windows_shortcut_path)
  if [ -z "${lnk_path}" ] || [ ! -f "${lnk_path}" ]; then
    return
  fi

  local target
  target=$(resolve_windows_lnk_target "${lnk_path}")
  if [ -z "${target}" ]; then
    return
  fi

  # Target is expected to be <parent>/AlipsaAccounting/AlipsaAccounting.exe
  local app_dir
  app_dir="$(dirname "${target}")"
  local parent_dir
  parent_dir="$(dirname "${app_dir}")"

  if is_valid_install "${parent_dir}"; then
    echo "${parent_dir}"
  fi
}

find_windows_shortcut_path() {
  local search_dirs=(
    "${HOME}/AppData/Roaming/Microsoft/Windows/Start Menu/Programs"
    "${ALLUSERSPROFILE:-/c/ProgramData}/Microsoft/Windows/Start Menu/Programs"
    "${HOME}/Desktop"
    "/c/Users/Public/Desktop"
  )
  for dir in "${search_dirs[@]}"; do
    if [ -d "${dir}" ]; then
      local found
      found=$(find "${dir}" -maxdepth 3 -name "${APP_NAME}.lnk" -print -quit 2>/dev/null || true)
      if [ -n "${found}" ]; then
        echo "${found}"
        return
      fi
    fi
  done
}

resolve_windows_lnk_target() {
  local lnk="$1"
  local win_lnk
  if command -v cygpath >/dev/null 2>&1; then
    win_lnk=$(cygpath -w "${lnk}")
  elif command -v wslpath >/dev/null 2>&1; then
    win_lnk=$(wslpath -w "${lnk}")
  else
    win_lnk="${lnk}"
  fi

  if command -v powershell.exe >/dev/null 2>&1; then
    powershell.exe -NoProfile -Command "(New-Object -ComObject WScript.Shell).CreateShortcut('${win_lnk//\'/''}').TargetPath" 2>/dev/null | tr -d '\r'
  fi
}

find_via_common_locations() {
  local -n locations
  case "${PLATFORM}" in
    linux)
      local linux_locations=(
        "${HOME}/.local/lib/${PACKAGE_NAME}"
        "${HOME}/programs/${PACKAGE_NAME}"
        "/opt/${PACKAGE_NAME}"
        "/opt/${APP_NAME}"
        "/usr/local/lib/${PACKAGE_NAME}"
      )
      locations=linux_locations
      ;;
    windows)
      local windows_locations=(
        "${HOME}/AppData/Local/${PACKAGE_NAME}"
        "${HOME}/AppData/Local/Programs/${PACKAGE_NAME}"
        "${HOME}/programs/${PACKAGE_NAME}"
        "/c/Program Files/${APP_NAME}"
        "/c/ProgramData/${APP_NAME}"
      )
      locations=windows_locations
      ;;
    macos)
      local macos_locations=(
        "/Applications/${APP_NAME}"
        "${HOME}/Applications/${APP_NAME}"
        "/opt/${PACKAGE_NAME}"
        "${HOME}/programs/${PACKAGE_NAME}"
      )
      locations=macos_locations
      ;;
  esac

  for candidate in "${locations[@]}"; do
    if is_valid_install "${candidate}"; then
      echo "${candidate}"
      return
    fi
  done
}

print_not_found_error() {
  echo "Error: could not find an existing ${APP_NAME} installation." >&2
  echo "" >&2
  echo "Searched shortcuts/launchers:" >&2
  case "${PLATFORM}" in
    linux)
      echo "  ${XDG_DATA_HOME:-${HOME}/.local/share}/applications/${APP_NAME}.desktop" >&2
      ;;
    macos)
      echo "  /Applications/${APP_NAME}.app" >&2
      echo "  ${HOME}/Applications/${APP_NAME}.app" >&2
      ;;
    windows)
      echo "  Start Menu and Desktop .lnk files named ${APP_NAME}.lnk" >&2
      ;;
  esac
  echo "" >&2
  echo "Searched common locations:" >&2
  local dummy
  dummy=$(find_via_common_locations 2>/dev/null || true)
  case "${PLATFORM}" in
    linux)
      echo "  ${HOME}/.local/lib/${PACKAGE_NAME}" >&2
      echo "  ${HOME}/programs/${PACKAGE_NAME}" >&2
      echo "  /opt/${PACKAGE_NAME}" >&2
      echo "  /opt/${APP_NAME}" >&2
      echo "  /usr/local/lib/${PACKAGE_NAME}" >&2
      ;;
    windows)
      echo "  ${HOME}/AppData/Local/${PACKAGE_NAME}" >&2
      echo "  ${HOME}/AppData/Local/Programs/${PACKAGE_NAME}" >&2
      echo "  ${HOME}/programs/${PACKAGE_NAME}" >&2
      echo "  /c/Program Files/${APP_NAME}" >&2
      echo "  /c/ProgramData/${APP_NAME}" >&2
      ;;
    macos)
      echo "  /Applications/${APP_NAME}" >&2
      echo "  ${HOME}/Applications/${APP_NAME}" >&2
      echo "  /opt/${PACKAGE_NAME}" >&2
      echo "  ${HOME}/programs/${PACKAGE_NAME}" >&2
      ;;
  esac
  echo "" >&2
  echo "Re-run with the installation parent directory:" >&2
  echo "  $0 --dir <path>" >&2
}

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

ZIP_FILE="${SCRIPT_DIR}/app/build/$(zip_release_dir "${PLATFORM}")/${PACKAGE_NAME}-${VERSION}-${PLATFORM}.zip"
if [ ! -f "${ZIP_FILE}" ]; then
  echo "Error: release package not found: ${ZIP_FILE}" >&2
  echo "Run $0 without --no-build to build it first." >&2
  exit 1
fi

INSTALL_DIR=$(find_existing_install)
if [ -z "${INSTALL_DIR}" ]; then
  print_not_found_error
  exit 1
fi

echo "Updating ${APP_NAME} ${VERSION} under ${INSTALL_DIR}..."

# Remove old app image and bundled scripts.
for entry in "${APP_NAME}" "${APP_NAME}.app" install.sh uninstall.sh skill; do
  if [ -e "${INSTALL_DIR}/${entry}" ]; then
    rm -rf "${INSTALL_DIR}/${entry}"
  fi
done

echo "  Extracting ${ZIP_FILE}..."
unzip -q "${ZIP_FILE}" -d "${INSTALL_DIR}"

# Make launcher executable on Linux/macOS.
case "${PLATFORM}" in
  linux|macos)
    LAUNCHER="${INSTALL_DIR}/${APP_NAME}/bin/${APP_NAME}"
    if [ -f "${LAUNCHER}" ]; then
      chmod +x "${LAUNCHER}"
    fi
    ;;
  windows)
    LAUNCHER="${INSTALL_DIR}/${APP_NAME}/${APP_NAME}.exe"
    ;;
esac

# On Linux, run the bundled install.sh to refresh the desktop entry.
if [ "${PLATFORM}" = "linux" ]; then
  INSTALL_SCRIPT="${INSTALL_DIR}/install.sh"
  if [ -x "${INSTALL_SCRIPT}" ]; then
    echo "  Registering desktop entry..."
    (cd "${INSTALL_DIR}" && ./install.sh)
  fi
fi

echo ""
echo "Updated ${APP_NAME} ${VERSION}."
echo "  App dir:  ${INSTALL_DIR}/${APP_NAME}"
echo "  Launcher: ${LAUNCHER}"
echo ""
echo "Start from the applications menu or run: ${LAUNCHER}"
