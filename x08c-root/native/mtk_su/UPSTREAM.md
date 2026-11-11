# Upstream and local changes

This source tree is derived from [`0xf15h/mtk_su`](https://github.com/0xf15h/mtk_su) at commit:

```text
ee36bf052779212a80ddfbdf226be0aaaeb80b08
```

The X08C/X08E branch changes the kernel-symbol discovery, credential handling, SELinux flow, logging, cleanup, cache eviction, and post-root command execution used by the APK. The unmodified upstream project and this derivative are licensed under GPL-3.0; see [`LICENSE`](LICENSE).

The checked-in source is canonical. Generated binaries, the upstream `.git` directory, NDK output, build caches, and virtual environments are intentionally excluded.
