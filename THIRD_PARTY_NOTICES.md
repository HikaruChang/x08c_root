# Third-party notices

## mtk_su

- Upstream: <https://github.com/0xf15h/mtk_su>
- Base commit: `ee36bf052779212a80ddfbdf226be0aaaeb80b08`
- License: GPL-3.0
- Corresponding modified source: [`x08c-root/native/mtk_su/`](x08c-root/native/mtk_su/)

## Dropbear SSH

- Version embedded in the APK: 2022.83
- Upstream: <https://matt.ucc.asn.au/dropbear/dropbear.html>
- License notice: [`licenses/DROPBEAR.txt`](licenses/DROPBEAR.txt)

The repository does not include the temporary Dropbear build tree or compiler output. The license permits binary redistribution subject to its notices, which are included above.

## Xiaomi MicoLauncher Base Binary

- Upstream package: `com.xiaomi.micolauncher` (`101.5.100-soundboxNormal`, `versionCode 1010005100`)
- Copyright: Xiaomi Inc. (proprietary stock system APK modified in-place at `classes4.dex` / `classes5.dex` and re-signed with debug key)
- Original patch implementation added in this repository: [`x08e-micolauncher/src/com/xiaomi/micolauncher/feature/appscreen/LocalAppsHelper.java`](x08e-micolauncher/src/com/xiaomi/micolauncher/feature/appscreen/LocalAppsHelper.java) and [`x08e-micolauncher/scripts/build_and_deploy.py`](x08e-micolauncher/scripts/build_and_deploy.py)
