# X08C Root 1.0.1

- 包名：`moe.rua.x08croot`
- `versionCode`：101
- `versionName`：1.0.1
- Android：minSdk / targetSdk 28
- ABI：`armeabi-v7a`
- 发布 APK：[`../dist/x08c-root-v1.0.1.apk`](../dist/x08c-root-v1.0.1.apk)
- SHA-256：`da8ee632b3c436f697e397b3c392d933d6406ef982ca1b5e42e33116e039ad53`

## 能力边界

应用包含针对 MT8167 / Android 9 的 `mtk_su` 衍生实现。成功状态要求 native 路径回读 UID 0，且 post-root 阶段实际确认 root ADB 与 SSH；只有进程启动、端口打开、命令返回 0 或屏幕显示绿色状态都不能单独证明 root。

该实现经过特定实机与固件验证，不是 X08A/X08C/X08E 全版本通用工具。不得把本仓库的 APK 兼容性外推为 boot 镜像或固件可以跨型号混刷。

SSH 使用公开临时凭据 `root` / `areuok`，监听 2222；ADB 使用 5555。只在隔离网络中短时使用，结束后重启设备。

## 源码布局

```text
AndroidManifest.xml
src/moe/rua/x08croot/       Java UI、调度与安全门控
res/                         Android 资源
postroot.sh                  临时 ADB / SSH 后处理
native/mtk_su/               定制 native 源码与 GPL-3.0 许可证
```

`native/mtk_su` 基于 `0xf15h/mtk_su` 的提交 `ee36bf052779212a80ddfbdf226be0aaaeb80b08`。本仓库保留了对应修改后的源代码，但没有提交 NDK 输出、构建缓存、签名私钥或 Dropbear 构建目录。

发布 APK 是保留签名的已验证产物。自动 Release 工作流负责完整性、版本和签名验证，不在 GitHub runner 上重新编译内核利用与 Dropbear。

## 版权

界面显示：

```text
Powered by NyphexAI. Developed by Hikaru(i@rua.moe)
```
