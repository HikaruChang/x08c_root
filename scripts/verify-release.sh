#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TAG=${1:-}
EXPECTED_CERT=d4bb64156f9715b0a5afd3200ce9052b6471b1bf4189c1de17f41a5bf1704570
COPYRIGHT='Powered by NyphexAI. Developed by Hikaru(i@rua.moe)'

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

sdk_tool() {
    local name=$1
    local sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
    if [ -z "$sdk" ] && [ -d "$HOME/Library/Android/sdk" ]; then
        sdk="$HOME/Library/Android/sdk"
    fi
    [ -n "$sdk" ] || return 1
    find "$sdk/build-tools" -type f -name "$name" 2>/dev/null | sort -V | tail -n 1
}

verify_one() {
    local component=$1
    local version=$2
    local package_name=$3
    local apk="$ROOT/dist/${component}-v${version}.apk"
    local manifest="$ROOT/$component/AndroidManifest.xml"
    local expected actual apksigner aapt2 certs badging dex_strings

    [ -f "$apk" ] || { echo "missing APK: $apk" >&2; exit 1; }
    [ -f "$manifest" ] || { echo "missing manifest: $manifest" >&2; exit 1; }
    grep -Fq "android:versionName=\"$version\"" "$manifest"
    grep -Fq "package=\"$package_name\"" "$manifest"

    expected=$(awk -v file="$(basename "$apk")" '$2 == file { print $1 }' "$ROOT/dist/CHECKSUMS.sha256")
    [ -n "$expected" ] || { echo "checksum entry missing: $apk" >&2; exit 1; }
    actual=$(sha256_of "$apk")
    [ "$actual" = "$expected" ] || {
        echo "SHA-256 mismatch for $apk" >&2
        echo "expected: $expected" >&2
        echo "actual:   $actual" >&2
        exit 1
    }

    unzip -tq "$apk" >/dev/null
    dex_strings=$(unzip -p "$apk" classes.dex | strings)
    grep -Fq "$COPYRIGHT" <<< "$dex_strings" || {
        echo "copyright string missing from $apk" >&2; exit 1
    }

    apksigner=$(sdk_tool apksigner) || {
        echo "Android SDK apksigner not found" >&2; exit 1
    }
    aapt2=$(sdk_tool aapt2) || {
        echo "Android SDK aapt2 not found" >&2; exit 1
    }

    "$apksigner" verify --verbose "$apk" >/dev/null
    certs=$("$apksigner" verify --print-certs "$apk")
    grep -Fq "Signer #1 certificate SHA-256 digest: $EXPECTED_CERT" <<< "$certs" || {
        echo "signer certificate mismatch for $apk" >&2
        printf '%s\n' "$certs" >&2
        exit 1
    }

    badging=$("$aapt2" dump badging "$apk")
    grep -Fq "package: name='$package_name'" <<< "$badging" || {
        echo "package name mismatch for $apk" >&2; exit 1
    }
    grep -Fq "versionName='$version'" <<< "$badging" || {
        echo "versionName mismatch for $apk" >&2; exit 1
    }

    echo "verified: $(basename "$apk")"
    echo "sha256:  $actual"
}

case "$TAG" in
    "")
        verify_one x08c-root 1.0.1 moe.rua.x08croot
        verify_one x08e-launcher 1.0.0 moe.rua.x08clauncher
        ;;
    x08c-root-v*)
        verify_one x08c-root "${TAG#x08c-root-v}" moe.rua.x08croot
        ;;
    x08e-launcher-v*)
        verify_one x08e-launcher "${TAG#x08e-launcher-v}" moe.rua.x08clauncher
        ;;
    *)
        echo "unsupported release tag: $TAG" >&2
        exit 2
        ;;
esac
