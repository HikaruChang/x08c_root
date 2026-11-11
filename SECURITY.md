# Security policy

## Safe use

This project changes kernel credentials and exposes temporary root services. Use it only on hardware you own or are explicitly authorized to test.

The X08C Root APK starts SSH on TCP 2222 with the public temporary credential `root` / `areuok`, and may expose ADB on TCP 5555. Use an isolated trusted network, do not forward these ports, and reboot the device when testing is finished.

Do not mix X08A, X08C and X08E firmware or boot images. This repository intentionally distributes no boot image or flashing automation.

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting / Security Advisory feature for issues that could expose signing material, enable unintended remote access, or affect devices outside the documented scope. Do not include private keys, full device identifiers or credentials in a public issue.
