# Release process

This repository publishes two independent APKs. A release tag must use one of these forms:

- `x08c-root-v<version>`
- `x08e-launcher-v<version>`

The version suffix must exactly match `android:versionName` in that component's `AndroidManifest.xml`, and the matching versioned APK must already exist in `dist/`.

On a tag push, `.github/workflows/release.yml` runs `scripts/verify-release.sh`, creates a one-file checksum, and uses the GitHub CLI to create the Release. The workflow has only `contents: write` permission.

## Evidence boundary

The workflow publishes the already signed, reviewed APK. It does **not** rebuild the native exploit or Dropbear and therefore does not claim bit-for-bit reproducibility from a clean runner. A green workflow means the checked-in artifact passed the pinned hash, ZIP, manifest, signer-certificate and copyright checks; it is not new live-device root evidence.

## Updating an app

1. Update source and `AndroidManifest.xml`.
2. Build, sign and test the APK on the intended model/firmware.
3. Replace only the matching versioned file in `dist/`.
4. Update `dist/CHECKSUMS.sha256` and the component README.
5. Run `./scripts/verify-release.sh` locally.
6. Commit and push `main`.
7. Create and push the matching annotated tag.

Never commit a keystore, signing password, ADB private key, device backup, boot image, runtime identifier or live-device log.
