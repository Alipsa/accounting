#!/usr/bin/env bash
#
# localUpdate.sh
#
# Updates an existing Alipsa Accounting installation to the locally built
# version without creating a formal release.
#
# The update is applied using the same platform-independent app-<version>.zip
# distribution that the in-app updater uses:
#   - jpackage installs: JAR files are replaced and .cfg files are rewritten.
#   - Gradle distZip portable installs: bin/, lib/ and skill/ are replaced.
#
# Supported platforms:
#   - Linux
#   - macOS
#   - Windows (Git Bash / MSYS2 / Cygwin)
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
LINUX_WM_CLASS="se-alipsa-accounting-AlipsaAccounting"
BUILD=true
INSTALL_DIR="${INSTALL_DIR:-}"

debug_log() {
  if [ "${ALIPSA_UPDATE_DEBUG:-false}" = "true" ]; then
    echo "DEBUG: $*" >&2
  fi
}

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
      sed -n '2,25p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Usage: $0 [--no-build] [--dir <path>]" >&2
      exit 1
      ;;
  esac
done

# Returns the platform-specific lib directory for app-*.jar, or empty string.
find_lib_dir() {
  local install_dir="$1"
  local candidates=()

  case "${PLATFORM}" in
    linux)
      candidates=(
        "${install_dir}/${APP_NAME}/lib/app"
        "${install_dir}/${APP_NAME}/lib"
        "${install_dir}/lib/app"
        "${install_dir}/lib"
      )
      ;;
    macos)
      candidates=(
        "${install_dir}/${APP_NAME}.app/Contents/app"
        "${install_dir}/${APP_NAME}.app/Contents/lib"
      )
      ;;
    windows)
      candidates=(
        "${install_dir}/${APP_NAME}/app"
        "${install_dir}/${APP_NAME}/lib"
        "${install_dir}/app"
        "${install_dir}/lib"
      )
      ;;
  esac

  for cand in "${candidates[@]}"; do
    if [ -d "${cand}" ] && [ -n "$(find "${cand}" -maxdepth 1 -type f -name 'app-*.jar' -print -quit 2>/dev/null || true)" ]; then
      echo "${cand}"
      return
    fi
  done
}

# Returns a plausible launcher path for the given lib dir, or empty string.
launcher_path_for_lib_dir() {
  local lib_dir="$1"
  local parent
  parent=$(dirname "${lib_dir}")

  case "${lib_dir}" in
    */lib/app)
      # Linux jpackage app image: <app>/lib/app -> <app>/bin/<APP_NAME>
      echo "$(dirname "${parent}")/bin/${APP_NAME}"
      ;;
    */Contents/app)
      # macOS jpackage app bundle: <app>.app/Contents/app -> <app>.app/Contents/MacOS/<APP_NAME>
      echo "${parent}/MacOS/${APP_NAME}"
      ;;
    */app)
      # Windows jpackage install: <install>/app -> <install>/<APP_NAME>.exe
      echo "${parent}/${APP_NAME}.exe"
      ;;
    */lib)
      # Gradle distZip portable install: <install>/lib -> <install>/bin/<APP_NAME>
      if [ "${PLATFORM}" = "windows" ]; then
        echo "${parent}/bin/${APP_NAME}.bat"
      else
        echo "${parent}/bin/${APP_NAME}"
      fi
      ;;
    *)
      echo ""
      ;;
  esac
}

# Returns the actual application root directory for the given lib dir.
install_root_for_lib_dir() {
  local lib_dir="$1"
  local parent
  parent=$(dirname "${lib_dir}")

  case "${lib_dir}" in
    */lib/app|*/Contents/app)
      dirname "${parent}"
      ;;
    */app|*/lib)
      echo "${parent}"
      ;;
    *)
      echo ""
      ;;
  esac
}

is_valid_install() {
  local dir="$1"
  case "${PLATFORM}" in
    linux)
      [ -f "${dir}/${APP_NAME}/bin/${APP_NAME}" ] ||
      [ -f "${dir}/${APP_NAME}/lib/app/${APP_NAME}.cfg" ] ||
      [ -f "${dir}/bin/${APP_NAME}" ] ||
      [ -f "${dir}/lib/app/${APP_NAME}.cfg" ]
      ;;
    macos)
      [ -d "${dir}/${APP_NAME}.app" ] && {
        [ -f "${dir}/${APP_NAME}.app/Contents/MacOS/${APP_NAME}" ] ||
        [ -f "${dir}/${APP_NAME}.app/Contents/app/${APP_NAME}.cfg" ]
      }
      ;;
    windows)
      [ -f "${dir}/${APP_NAME}.exe" ] ||
      [ -f "${dir}/app/${APP_NAME}.cfg" ] ||
      [ -f "${dir}/bin/${APP_NAME}.bat" ] ||
      [ -f "${dir}/${APP_NAME}/${APP_NAME}.exe" ] ||
      [ -f "${dir}/${APP_NAME}/app/${APP_NAME}.cfg" ] ||
      [ -f "${dir}/${APP_NAME}/bin/${APP_NAME}.bat" ]
      ;;
  esac
}

is_portable_lib_dir() {
  # A plain .../lib directory (not jpackage's .../lib/app) is a Gradle distZip portable install.
  [[ "$1" == */lib && "$1" != */lib/app ]]
}

build_classpath_block() {
  local main_jar="$1"
  local lib_dir="$2"
  local jar_name

  echo "app.classpath=\$APPDIR/${main_jar}"
  for jar in "${lib_dir}"/*.jar; do
    jar_name=$(basename "${jar}")
    [ "${jar_name}" = "${main_jar}" ] && continue
    echo "app.classpath=\$APPDIR/${jar_name}"
  done
}

update_portable_install() {
  local install_root="$1"
  local extracted_dir="$2"

  echo "  Replacing portable distribution files..."
  for entry in bin lib skill; do
    if [ -e "${install_root}/${entry}" ]; then
      rm -rf "${install_root}/${entry}"
    fi
  done
  for entry in bin lib skill; do
    if [ -d "${extracted_dir}/${entry}" ]; then
      mv "${extracted_dir}/${entry}" "${install_root}/"
    fi
  done
}

update_linux_desktop_entry() {
  [ "${PLATFORM}" = "linux" ] || return

  local desktop_file="${XDG_DATA_HOME:-${HOME}/.local/share}/applications/${APP_NAME}.desktop"
  [ -f "${desktop_file}" ] || return

  if grep -q '^StartupWMClass=' "${desktop_file}"; then
    sed -i.bak "s#^StartupWMClass=.*#StartupWMClass=${LINUX_WM_CLASS}#" "${desktop_file}"
  else
    printf '\nStartupWMClass=%s\n' "${LINUX_WM_CLASS}" >> "${desktop_file}"
  fi
  if grep -q '^StartupNotify=' "${desktop_file}"; then
    sed -i.bak "s#^StartupNotify=.*#StartupNotify=true#" "${desktop_file}"
  else
    printf 'StartupNotify=true\n' >> "${desktop_file}"
  fi
  rm -f "${desktop_file}.bak"

  if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$(dirname "${desktop_file}")" >/dev/null 2>&1 || true
  fi
  if command -v xdg-desktop-menu >/dev/null 2>&1; then
    xdg-desktop-menu forceupdate >/dev/null 2>&1 || true
  fi
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
  exec_path=$(grep "^Exec=" "${desktop_file}" | head -n 1 | sed -e 's/^Exec=//' -e 's/^"//' -e 's/"$//')
  if [ -z "${exec_path}" ]; then
    return
  fi

  # Exec path is expected to end in .../AlipsaAccounting/bin/AlipsaAccounting
  local parent_dir
  parent_dir="$(dirname "$(dirname "$(dirname "${exec_path}")")")"

  if is_valid_install "${parent_dir}"; then
    echo "${parent_dir}"
  fi
}

find_via_macos_app_bundle() {
  local candidates=(
    "/Applications"
    "${HOME}/Applications"
  )
  for parent in "${candidates[@]}"; do
    if [ -d "${parent}/${APP_NAME}.app" ] && is_valid_install "${parent}"; then
      echo "${parent}"
      return
    fi
  done
}

windows_path_to_unix() {
  local path="$1"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -u "${path}" 2>/dev/null || echo "${path}"
  else
    echo "${path}"
  fi
}

find_via_windows_shortcut() {
  local candidate
  candidate=$(find_via_windows_shortcut_file)
  if [ -n "${candidate}" ]; then
    echo "${candidate}"
    return
  fi

  if [ "${ALIPSA_UPDATE_USE_POWERSHELL:-false}" = "true" ]; then
    find_via_windows_shortcut_powershell
  fi
}

find_via_windows_shortcut_file() {
  local root shortcut target candidate
  while IFS= read -r root; do
    while IFS= read -r shortcut; do
      debug_log "Found shortcut: ${shortcut}"
      while IFS= read -r target; do
        debug_log "Extracted shortcut target: ${target:-<none>}"
        candidate=$(install_dir_for_windows_launcher "${target}")
        if [ -n "${candidate}" ]; then
          echo "${candidate}"
          return
        fi
      done < <(windows_shortcut_targets_from_file "${shortcut}")
    done < <(find "${root}" -type f -iname "${APP_NAME}*.lnk" -print 2>/dev/null)
  done < <(windows_shortcut_roots)
}

windows_shortcut_roots() {
  local candidates=(
    "${HOME}/Desktop"
    "${HOME}/OneDrive/Desktop"
    "${HOME}/OneDrive/Skrivbord"
  )

  [ -n "${USERPROFILE:-}" ] && candidates+=("${USERPROFILE}/Desktop")
  [ -n "${USERPROFILE:-}" ] && candidates+=("${USERPROFILE}/OneDrive/Desktop")
  [ -n "${USERPROFILE:-}" ] && candidates+=("${USERPROFILE}/OneDrive/Skrivbord")
  [ -n "${PUBLIC:-}" ] && candidates+=("${PUBLIC}/Desktop")
  [ -n "${APPDATA:-}" ] && candidates+=("${APPDATA}/Microsoft/Windows/Start Menu")
  [ -n "${ProgramData:-}" ] && candidates+=("${ProgramData}/Microsoft/Windows/Start Menu")
  [ -n "${PROGRAMDATA:-}" ] && candidates+=("${PROGRAMDATA}/Microsoft/Windows/Start Menu")
  [ -n "${OneDrive:-}" ] && candidates+=("${OneDrive}/Desktop")
  [ -n "${OneDrive:-}" ] && candidates+=("${OneDrive}/Skrivbord")
  [ -n "${OneDriveConsumer:-}" ] && candidates+=("${OneDriveConsumer}/Desktop")
  [ -n "${OneDriveConsumer:-}" ] && candidates+=("${OneDriveConsumer}/Skrivbord")

  local root unix_root
  for root in "${candidates[@]}"; do
    [ -n "${root}" ] || continue
    unix_root=$(windows_path_to_unix "${root}")
    if [ -d "${unix_root}" ]; then
      debug_log "Windows shortcut root: ${unix_root}"
      echo "${unix_root}"
    else
      debug_log "Windows shortcut root missing: ${unix_root}"
    fi
  done
}

windows_shortcut_targets_from_file() {
  local shortcut="$1"

  debug_log "Inspecting shortcut: ${shortcut}"
  {
    if command -v strings >/dev/null 2>&1; then
      strings -el "${shortcut}" 2>/dev/null || true
      strings -a "${shortcut}" 2>/dev/null || true
    fi
    tr -d '\000' < "${shortcut}" 2>/dev/null || true
  } | LC_ALL=C awk -v app="${APP_NAME}" '
    index($0, app ".exe") || index($0, app ".bat") {
      while (match($0, /[A-Za-z]:\\[A-Za-z0-9_.(){}$&+@!#%=~^, -][^"'\''\r\n]*(AlipsaAccounting\.exe|AlipsaAccounting\.bat)/)) {
        print substr($0, RSTART, RLENGTH)
        $0 = substr($0, RSTART + RLENGTH)
      }
      while (match($0, /\/[A-Za-z]\/[^"'\''\r\n]*(AlipsaAccounting\.exe|AlipsaAccounting\.bat)/)) {
        print substr($0, RSTART, RLENGTH)
        $0 = substr($0, RSTART + RLENGTH)
      }
    }
  ' | awk '!seen[$0]++'
}

install_dir_for_windows_launcher() {
  local target="$1"
  [ -n "${target}" ] || return

  local target_path install_dir
  target_path=$(windows_path_to_unix "${target}")
  debug_log "Shortcut target candidate: ${target_path}"
  install_dir=$(dirname "${target_path}")
  if is_valid_install "${install_dir}"; then
    debug_log "Valid install from launcher directory: ${install_dir}"
    echo "${install_dir}"
    return
  fi

  install_dir=$(dirname "${install_dir}")
  if is_valid_install "${install_dir}"; then
    debug_log "Valid install from launcher parent directory: ${install_dir}"
    echo "${install_dir}"
  fi
}

windows_drive_roots() {
  local roots=()
  local letter root

  case "${PWD}" in
    /[a-zA-Z]/*)
      roots+=("/${PWD:1:1}")
      ;;
  esac

  if [ -n "${HOMEDRIVE:-}" ]; then
    root=$(windows_path_to_unix "${HOMEDRIVE}\\")
    roots+=("${root}")
  fi

  for letter in c d e f g h i j k l m n o p q r s t u v w x y z; do
    roots+=("/${letter}")
  done

  printf '%s\n' "${roots[@]}" | awk '!seen[$0]++'
}

windows_program_locations() {
  local locations=()

  [ -n "${LOCALAPPDATA:-}" ] && locations+=("${LOCALAPPDATA}")
  [ -n "${ProgramFiles:-}" ] && locations+=("${ProgramFiles}")
  [ -n "${PROGRAMFILES:-}" ] && locations+=("${PROGRAMFILES}")
  [ -n "${ProgramW6432:-}" ] && locations+=("${ProgramW6432}")
  [ -n "${PROGRAMW6432:-}" ] && locations+=("${PROGRAMW6432}")

  local drive unix_location
  while IFS= read -r drive; do
    [ -d "${drive}" ] || continue
    locations+=(
      "${drive}/programs"
      "${drive}/Programs"
      "${drive}/Program Files"
      "${drive}/Program Files (x86)"
    )
  done < <(windows_drive_roots)

  for location in "${locations[@]}"; do
    unix_location=$(windows_path_to_unix "${location}")
    echo "${unix_location}"
  done | awk '!seen[$0]++'
}

find_via_windows_shortcut_powershell() {
  local powershell
  if command -v powershell.exe >/dev/null 2>&1; then
    powershell=powershell.exe
  elif command -v pwsh.exe >/dev/null 2>&1; then
    powershell=pwsh.exe
  else
    return
  fi

  local shortcut_targets
  shortcut_targets=$("${powershell}" -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "
\$ErrorActionPreference = 'SilentlyContinue'
\$appName = '${APP_NAME}'
\$targetNames = @(\"${APP_NAME}.exe\", \"${APP_NAME}.bat\")
\$roots = @(
  [Environment]::GetFolderPath('Desktop'),
  [Environment]::GetFolderPath('CommonDesktopDirectory'),
  [Environment]::GetFolderPath('StartMenu'),
  [Environment]::GetFolderPath('CommonStartMenu'),
  [Environment]::GetFolderPath('Programs'),
  [Environment]::GetFolderPath('CommonPrograms')
) | Where-Object { \$_ -and (Test-Path -LiteralPath \$_) } | Select-Object -Unique
\$shell = New-Object -ComObject WScript.Shell
foreach (\$root in \$roots) {
  foreach (\$shortcut in Get-ChildItem -LiteralPath \$root -Filter \"\$appName*.lnk\" -Recurse -File) {
    \$target = \$shell.CreateShortcut(\$shortcut.FullName).TargetPath
    if (\$target -and (\$targetNames -contains [IO.Path]::GetFileName(\$target))) {
      \$target
    }
  }
}
" 2>/dev/null | tr -d '\r' || true)

  local target candidate
  while IFS= read -r target; do
    [ -n "${target}" ] || continue
    candidate=$(install_dir_for_windows_launcher "${target}")
    if [ -n "${candidate}" ]; then
      echo "${candidate}"
      return
    fi
  done <<< "${shortcut_targets}"
}

find_via_common_locations() {
  local locations=()
  case "${PLATFORM}" in
    linux)
      locations=(
        "${HOME}/.local/lib/${PACKAGE_NAME}"
        "${HOME}/programs/${PACKAGE_NAME}"
        "/opt/${PACKAGE_NAME}"
        "/opt/${APP_NAME}"
        "/usr/local/lib/${PACKAGE_NAME}"
      )
      ;;
    macos)
      locations=(
        "/Applications"
        "${HOME}/Applications"
        "/opt/${PACKAGE_NAME}"
        "${HOME}/programs/${PACKAGE_NAME}"
      )
      ;;
    windows)
      mapfile -t locations < <(windows_program_locations)
      ;;
  esac

  for candidate in "${locations[@]}"; do
    debug_log "Checking common location: ${candidate}"
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
      echo "  Desktop and Start Menu ${APP_NAME}*.lnk shortcuts" >&2
      ;;
  esac
  echo "" >&2
  echo "Searched common locations:" >&2
  case "${PLATFORM}" in
    linux)
      echo "  ${HOME}/.local/lib/${PACKAGE_NAME}" >&2
      echo "  ${HOME}/programs/${PACKAGE_NAME}" >&2
      echo "  /opt/${PACKAGE_NAME}" >&2
      echo "  /opt/${APP_NAME}" >&2
      echo "  /usr/local/lib/${PACKAGE_NAME}" >&2
      ;;
    macos)
      echo "  /Applications" >&2
      echo "  ${HOME}/Applications" >&2
      echo "  /opt/${PACKAGE_NAME}" >&2
      echo "  ${HOME}/programs/${PACKAGE_NAME}" >&2
      ;;
    windows)
      echo "  %LOCALAPPDATA%" >&2
      echo "  %ProgramFiles%" >&2
      echo "  /<drive>/programs" >&2
      echo "  /<drive>/Program Files" >&2
      ;;
  esac
  echo "" >&2
  echo "Re-run with the installation directory:" >&2
  echo "  $0 --dir <path>" >&2
}

if [ "${BUILD}" = true ]; then
  echo "Building distribution zip..."
  cd "${SCRIPT_DIR}"
  ./gradlew :app:distZip
fi

VERSION=$(cd "${SCRIPT_DIR}" && ./gradlew :app:properties -q 2>/dev/null | grep "^version:" | awk '{print $2}')
if [ -z "${VERSION}" ]; then
  echo "Error: could not determine project version." >&2
  exit 1
fi

ZIP_FILE="${SCRIPT_DIR}/app/build/distributions/app-${VERSION}.zip"
if [ ! -f "${ZIP_FILE}" ]; then
  echo "Error: distribution zip not found: ${ZIP_FILE}" >&2
  echo "Run $0 without --no-build to build it first." >&2
  exit 1
fi

INSTALL_DIR=$(find_existing_install)
if [ -z "${INSTALL_DIR}" ]; then
  print_not_found_error
  exit 1
fi

LIB_DIR=$(find_lib_dir "${INSTALL_DIR}")
if [ -z "${LIB_DIR}" ]; then
  echo "Error: could not locate the JAR directory in ${INSTALL_DIR}." >&2
  exit 1
fi

INSTALL_ROOT=$(install_root_for_lib_dir "${LIB_DIR}")
if [ -z "${INSTALL_ROOT}" ]; then
  INSTALL_ROOT="${INSTALL_DIR}"
fi

echo "Updating ${APP_NAME} ${VERSION} under ${INSTALL_ROOT}..."

STAGING_DIR=$(mktemp -d "${TMPDIR:-/tmp}/alipsa-update-XXXXXX")
APP_DIR_NAME="app-${VERSION}"
BACKUP_DIR="${LIB_DIR}/.update-backup"

cleanup_staging() {
  rm -rf "${STAGING_DIR}"
}
trap cleanup_staging EXIT

echo "  Extracting ${ZIP_FILE}..."
unzip -oq "${ZIP_FILE}" -d "${STAGING_DIR}"

NEW_MAIN_JAR=$(find "${STAGING_DIR}/${APP_DIR_NAME}/lib" -type f -name 'app-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit 2>/dev/null || true)
if [ -z "${NEW_MAIN_JAR}" ]; then
  echo "Error: could not find the new main JAR in ${ZIP_FILE}." >&2
  exit 1
fi
NEW_MAIN_JAR=$(basename "${NEW_MAIN_JAR}")

if is_portable_lib_dir "${LIB_DIR}"; then
  update_portable_install "${INSTALL_ROOT}" "${STAGING_DIR}/${APP_DIR_NAME}"
else
  echo "  Backing up current JARs..."
  rm -rf "${BACKUP_DIR}"
  mkdir -p "${BACKUP_DIR}"
  mv "${LIB_DIR}/"*.jar "${BACKUP_DIR}/"

  echo "  Copying updated JARs..."
  if ! cp "${STAGING_DIR}/${APP_DIR_NAME}/lib/"*.jar "${LIB_DIR}/"; then
    echo "Error: failed to copy updated JARs; restoring backup." >&2
    mv "${BACKUP_DIR}/"*.jar "${LIB_DIR}/"
    rm -rf "${BACKUP_DIR}"
    exit 1
  fi

  echo "  Updating launcher configuration..."
  CLASSPATH_BLOCK=$(build_classpath_block "${NEW_MAIN_JAR}" "${LIB_DIR}")
  WINDOW_CLASS_OPTION=""
  if [ "${PLATFORM}" = "linux" ]; then
    WINDOW_CLASS_OPTION="java-options=-Dsun.awt.X11.XWMClass=${LINUX_WM_CLASS}"
  fi
  export CLASSPATH_BLOCK
  export WINDOW_CLASS_OPTION
  for cfg in "${LIB_DIR}"/*.cfg; do
    [ -f "${cfg}" ] || continue
    awk -v version="${VERSION}" '
      /^app\.classpath=/ { next }
      ENVIRON["WINDOW_CLASS_OPTION"] != "" && $0 == ENVIRON["WINDOW_CLASS_OPTION"] { next }
      ENVIRON["WINDOW_CLASS_OPTION"] != "" && /^java-options=-Dsun\.awt\.X11\.XWMClass=/ { next }
      /^java-options=-Djpackage\.app-version=/ {
        sub(/^java-options=-Djpackage\.app-version=.*/, "java-options=-Djpackage.app-version=" version)
        print
        next
      }
      /^\[JavaOptions\]$/ {
        print
        if (ENVIRON["WINDOW_CLASS_OPTION"] != "") {
          print ENVIRON["WINDOW_CLASS_OPTION"]
        }
        next
      }
      /^\[Application\]$/ { print; print ENVIRON["CLASSPATH_BLOCK"]; next }
      { print }
    ' "${cfg}" > "${cfg}.tmp"
    mv "${cfg}.tmp" "${cfg}"
  done

  echo "  Cleaning up..."
  rm -rf "${BACKUP_DIR}"
fi

LAUNCHER=$(launcher_path_for_lib_dir "${LIB_DIR}")
update_linux_desktop_entry

echo ""
echo "Updated ${APP_NAME} ${VERSION}."
echo "  App dir:  ${INSTALL_ROOT}"
if [ -n "${LAUNCHER}" ]; then
  echo "  Launcher: ${LAUNCHER}"
  echo ""
  echo "Start the app from the applications menu or run: ${LAUNCHER}"
else
  echo ""
  echo "Start the app from the applications menu."
fi
