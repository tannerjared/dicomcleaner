#!/usr/bin/env bash

set -euo pipefail

APP_NAME="DicomCleaner"
APP_VERSION="${APP_VERSION:-1.0.0}"
APP_IDENTIFIER="${APP_IDENTIFIER:-com.pixelmed.dicomcleaner}"
OUTPUT_DIR="${OUTPUT_DIR:-dist/macos}"
PACKAGE_TYPE="${PACKAGE_TYPE:-app-image}"
SKIP_BUILD=0
MAC_SIGN="${MAC_SIGN:-0}"
MAC_SIGNING_KEY_USER_NAME="${MAC_SIGNING_KEY_USER_NAME:-}"
JVM_MIN_MEMORY="${JVM_MIN_MEMORY:-256m}"
JVM_MAX_MEMORY="${JVM_MAX_MEMORY:-768m}"

usage() {
  cat <<'USAGE'
Build a native Apple Silicon macOS app bundle for DicomCleaner.

Usage:
  packaging/macos/build-apple-silicon-app.sh [options]

Options:
  --app-version VERSION   App/package version to pass to jpackage.
  --output DIR            Destination directory. Default: dist/macos
  --type TYPE             jpackage type: app-image, dmg, or pkg. Default: app-image
  --skip-build            Reuse an existing dist/dicomCleaner.jar.
  --sign                  Ask jpackage to sign the app/package.
  -h, --help              Show this help.

Environment:
  APP_VERSION                         Same as --app-version.
  APP_IDENTIFIER                      macOS bundle identifier. Default: com.pixelmed.dicomcleaner
  MAC_SIGN=1                          Same as --sign.
  MAC_SIGNING_KEY_USER_NAME=<name>    Developer ID signing identity name.
  JVM_MIN_MEMORY=256m                 Launcher -Xms setting.
  JVM_MAX_MEMORY=768m                 Launcher -Xmx setting.

This script must run on an Apple Silicon Mac with an arm64/aarch64 JDK that
includes jpackage. The bundled runtime determines whether the app runs natively.
USAGE
}

die() {
  echo "error: $*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --app-version)
      [[ $# -ge 2 ]] || die "--app-version requires a value"
      APP_VERSION="$2"
      shift 2
      ;;
    --output)
      [[ $# -ge 2 ]] || die "--output requires a value"
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --type)
      [[ $# -ge 2 ]] || die "--type requires a value"
      PACKAGE_TYPE="$2"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD=1
      shift
      ;;
    --sign)
      MAC_SIGN=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown option: $1"
      ;;
  esac
done

case "$PACKAGE_TYPE" in
  app-image|dmg|pkg) ;;
  *) die "--type must be one of: app-image, dmg, pkg" ;;
esac

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd -P)"
cd "$REPO_ROOT"

[[ "$(uname -s)" == "Darwin" ]] || die "macOS is required for jpackage macOS output"
[[ "$(uname -m)" == "arm64" ]] || die "run this on Apple Silicon, not under an Intel/Rosetta shell"

command -v java >/dev/null 2>&1 || die "install an Apple Silicon JDK first"
command -v javac >/dev/null 2>&1 || die "install a full JDK, not only a JRE"
command -v jpackage >/dev/null 2>&1 || die "install JDK 14 or newer; jpackage was not found"
if [[ "$SKIP_BUILD" -eq 0 ]]; then
  command -v ant >/dev/null 2>&1 || die "install Apache Ant"
fi

JAVA_ARCH="$(
  java -XshowSettings:properties -version 2>&1 |
    awk -F= '/^[[:space:]]*os.arch =/ { gsub(/^[ \t]+|[ \t]+$/, "", $2); print $2; exit }'
)"

case "$JAVA_ARCH" in
  aarch64|arm64) ;;
  *) die "the active JDK is ${JAVA_ARCH:-unknown}; install/select an Apple Silicon JDK" ;;
esac

if [[ "$SKIP_BUILD" -eq 0 ]]; then
  ant dist
fi

[[ -f "dist/dicomCleaner.jar" ]] || die "dist/dicomCleaner.jar was not built"
[[ -f "icons/DicomCleaner.icns" ]] || die "icons/DicomCleaner.icns is missing"

rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

JPACKAGE_INPUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/dicomcleaner-jpackage.XXXXXX")"
trap 'rm -rf "$JPACKAGE_INPUT_DIR"' EXIT
cp "dist/dicomCleaner.jar" "$JPACKAGE_INPUT_DIR/"

JPACKAGE_ARGS=(
  --type "$PACKAGE_TYPE"
  --name "$APP_NAME"
  --app-version "$APP_VERSION"
  --vendor "PixelMed Publishing"
  --description "Local DICOM input and cleaned local output tool"
  --input "$JPACKAGE_INPUT_DIR"
  --main-jar "dicomCleaner.jar"
  --main-class "org.eclipse.jdt.internal.jarinjarloader.JarRsrcLoader"
  --dest "$OUTPUT_DIR"
  --icon "icons/DicomCleaner.icns"
  --mac-package-name "$APP_NAME"
  --mac-package-identifier "$APP_IDENTIFIER"
  --mac-app-category "medical"
  --java-options "-Xms${JVM_MIN_MEMORY}"
  --java-options "-Xmx${JVM_MAX_MEMORY}"
  --java-options "-Dapple.awt.application.name=${APP_NAME}"
  --java-options "-Dapple.laf.useScreenMenuBar=true"
  --java-options "--add-opens=java.base/java.nio=ALL-UNNAMED"
  --java-options "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED"
)

if [[ "$MAC_SIGN" == "1" ]]; then
  JPACKAGE_ARGS+=(--mac-sign)
  if [[ -n "$MAC_SIGNING_KEY_USER_NAME" ]]; then
    JPACKAGE_ARGS+=(--mac-signing-key-user-name "$MAC_SIGNING_KEY_USER_NAME")
  fi
fi

APP_PATH="${OUTPUT_DIR}/${APP_NAME}.app"
set +e
jpackage "${JPACKAGE_ARGS[@]}"
JPACKAGE_STATUS=$?
set -e

if [[ "$JPACKAGE_STATUS" -ne 0 ]]; then
  if [[ "$PACKAGE_TYPE" == "app-image" && -d "$APP_PATH" ]]; then
    echo "jpackage returned ${JPACKAGE_STATUS}; clearing app bundle extended attributes and retrying the app signature"
    CODESIGN_STATUS=1
    for attempt in 1 2 3; do
      sleep 1
      xattr -cr "$APP_PATH" || true
      xattr -c "$APP_PATH"
      xattr -d "com.apple.FinderInfo" "$APP_PATH" 2>/dev/null || true
      xattr -d "com.apple.fileprovider.fpfs#P" "$APP_PATH" 2>/dev/null || true
      xattr -c "$APP_PATH"
      set +e
      if [[ "$MAC_SIGN" == "1" && -n "$MAC_SIGNING_KEY_USER_NAME" ]]; then
        codesign -s "$MAC_SIGNING_KEY_USER_NAME" --force --deep --options runtime "$APP_PATH"
      else
        codesign -s - --force "$APP_PATH"
      fi
      CODESIGN_STATUS=$?
      set -e
      [[ "$CODESIGN_STATUS" -eq 0 ]] && break
      echo "codesign retry ${attempt} failed"
    done
    [[ "$CODESIGN_STATUS" -eq 0 ]] || exit "$CODESIGN_STATUS"
  else
    exit "$JPACKAGE_STATUS"
  fi
fi

if [[ -d "$APP_PATH" ]]; then
  file "${APP_PATH}/Contents/MacOS/${APP_NAME}"
  RUNTIME_LIBJLI="${APP_PATH}/Contents/runtime/Contents/Home/lib/libjli.dylib"
  if [[ -f "$RUNTIME_LIBJLI" ]]; then
    file "$RUNTIME_LIBJLI"
  fi
  echo "Built ${APP_PATH}"
else
  echo "Built ${PACKAGE_TYPE} output in ${OUTPUT_DIR}"
fi
