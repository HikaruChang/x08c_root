#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TAG=${1:-}
EXPECTED_CERT=d4bb64156f9715b0a5afd3200ce9052b6471b1bf4189c1de17f41a5bf1704570
MICOLAUNCHER_CERT=47774f2b9492f457de5b7ca61932c91154af18e9153143b8af198c37e3394911
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

check_checksum() {
    local file=$1
    local expected actual
    expected=$(awk -v name="$(basename "$file")" '$2 == name { print $1 }' "$ROOT/dist/CHECKSUMS.sha256")
    [ -n "$expected" ] || { echo "checksum entry missing: $file" >&2; exit 1; }
    actual=$(sha256_of "$file")
    [ "$actual" = "$expected" ] || {
        echo "SHA-256 mismatch for $file" >&2
        echo "expected: $expected" >&2
        echo "actual:   $actual" >&2
        exit 1
    }
    printf '%s' "$actual"
}

verify_apk_common() {
    local apk=$1
    local package_name=$2
    local version=$3
    local expected_cert=$4
    local dex_entry=$5
    local apksigner aapt2 certs badging dex_strings

    unzip -tq "$apk" >/dev/null
    dex_strings=$(unzip -p "$apk" "$dex_entry" | strings)
    grep -Fq "$COPYRIGHT" <<< "$dex_strings" || {
        echo "copyright string missing from $apk ($dex_entry)" >&2; exit 1
    }

    apksigner=$(sdk_tool apksigner) || {
        echo "Android SDK apksigner not found" >&2; exit 1
    }
    aapt2=$(sdk_tool aapt2) || {
        echo "Android SDK aapt2 not found" >&2; exit 1
    }

    "$apksigner" verify --verbose "$apk" >/dev/null
    certs=$("$apksigner" verify --print-certs "$apk")
    grep -Fq "certificate SHA-256 digest: $expected_cert" <<< "$certs" || {
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
}

verify_one() {
    local component=$1
    local version=$2
    local package_name=$3
    local apk="$ROOT/dist/${component}-v${version}.apk"
    local manifest="$ROOT/$component/AndroidManifest.xml"
    local actual

    [ -f "$apk" ] || { echo "missing APK: $apk" >&2; exit 1; }
    [ -f "$manifest" ] || { echo "missing manifest: $manifest" >&2; exit 1; }
    grep -Fq "android:versionName=\"$version\"" "$manifest"
    grep -Fq "package=\"$package_name\"" "$manifest"

    actual=$(check_checksum "$apk")
    verify_apk_common "$apk" "$package_name" "$version" "$EXPECTED_CERT" classes.dex

    echo "verified: $(basename "$apk")"
    echo "sha256:  $actual"
}

verify_micolauncher() {
    local version=$1
    local full_version="${version}-soundboxNormal"
    local apk="$ROOT/dist/x08e-micolauncher-v${version}.apk"
    local tarball="$ROOT/dist/x08e-micolauncher-v${version}-oat-arm.tar.gz"
    local helper="$ROOT/x08e-micolauncher/src/com/xiaomi/micolauncher/feature/appscreen/LocalAppsHelper.java"
    local actual_apk actual_tar tar_list

    [ -f "$apk" ] || { echo "missing APK: $apk" >&2; exit 1; }
    [ -f "$tarball" ] || { echo "missing OAT tarball: $tarball" >&2; exit 1; }
    [ -f "$helper" ] || { echo "missing helper source: $helper" >&2; exit 1; }
    grep -Fq "$COPYRIGHT" "$helper" || {
        echo "copyright string missing from $helper" >&2; exit 1
    }

    actual_apk=$(check_checksum "$apk")
    actual_tar=$(check_checksum "$tarball")
    verify_apk_common "$apk" "com.xiaomi.micolauncher" "$full_version" "$MICOLAUNCHER_CERT" classes5.dex

    tar_list=$(tar -tzf "$tarball")
    grep -Fxq "oat/arm/base.odex" <<< "$tar_list" || {
        echo "missing oat/arm/base.odex in $tarball" >&2; exit 1
    }
    grep -Fxq "oat/arm/base.vdex" <<< "$tar_list" || {
        echo "missing oat/arm/base.vdex in $tarball" >&2; exit 1
    }

    python3 - "$tarball" "$COPYRIGHT" <<'PY'
import sys, tarfile

tar_path, needle = sys.argv[1], sys.argv[2].encode("utf-8")
with tarfile.open(tar_path, "r:gz") as tf:
    odex = tf.extractfile("oat/arm/base.odex").read()
    vdex = tf.extractfile("oat/arm/base.vdex").read()
if b"oat\n138\x00" not in odex:
    raise SystemExit("invalid oat 138 header in base.odex")
if not vdex.startswith(b"vdex019\x00"):
    raise SystemExit("invalid vdex019 header in base.vdex")
if needle not in vdex:
    raise SystemExit("copyright string missing from base.vdex")
PY

    echo "verified: $(basename "$apk")"
    echo "sha256:  $actual_apk"
    echo "verified: $(basename "$tarball")"
    echo "sha256:  $actual_tar"
}

case "$TAG" in
    "")
        verify_one x08c-root 1.0.1 moe.rua.x08croot
        verify_one x08e-launcher 1.0.0 moe.rua.x08clauncher
        verify_micolauncher 101.5.100
        ;;
    x08c-root-v*)
        verify_one x08c-root "${TAG#x08c-root-v}" moe.rua.x08croot
        ;;
    x08e-launcher-v*)
        verify_one x08e-launcher "${TAG#x08e-launcher-v}" moe.rua.x08clauncher
        ;;
    x08e-micolauncher-v*)
        verify_micolauncher "${TAG#x08e-micolauncher-v}"
        ;;
    *)
        echo "unsupported release tag: $TAG" >&2
        exit 2
        ;;
esac
