#!/usr/bin/env python3
"""
Build and deploy script for modified Xiaomi MicoLauncher Apps page (Redmi X08E).

Workflow:
1. Compile LocalAppsHelper.java against android.jar + minimal Xiaomi stubs using javac + d8.
2. Patch LocalAppsHelper* classes into stock classes5.dex (DEX version 038).
3. Disassemble target classes from stock classes4.dex, apply exact Smali hooks,
   reassemble with Smali, and replace those ClassDefs in classes4.dex.
4. Package patched classes4.dex + classes5.dex into x08e-micolauncher.apk (zipalign + apksigner).
5. (--deploy or --deploy-apk) Run dex2oat --compiler-filter=quicken on X08E, align all 5 DEX
   location CRC32s in base.vdex and base.odex back to stock base.apk CRCs, install into oat/arm/,
   and restart Launcher without modifying /system or boot.
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile
from pathlib import Path
from typing import Dict, List, Optional, Tuple

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_DIR = SCRIPT_DIR.parent
HELPER_JAVA = PROJECT_DIR / "src/com/xiaomi/micolauncher/feature/appscreen/LocalAppsHelper.java"
OUT_APK = PROJECT_DIR / "x08e-micolauncher.apk"
OUT_OAT_DIR = PROJECT_DIR / "oat/arm"

REMOTE_STOCK_BACKUP = "/data/local/tmp/micolauncher_oat_stock_backup"
REMOTE_WORK_DIR = "/data/local/tmp/mico_patch"
REMOTE_CLC = "PCL[/system/framework/com.xiaomi.ot.jar:/system/framework/org.apache.http.legacy.boot.jar]"

TARGET_CLASSES_4 = [
    "Lcom/xiaomi/micolauncher/feature/appscreen/fragment/AppHomeFragment;",
    "Lcom/xiaomi/micolauncher/feature/appscreen/viewholder/j;",
    "Lcom/xiaomi/micolauncher/feature/appscreen/viewholder/m;",
    "Lcom/xiaomi/micolauncher/module/homepage/d$a;",
    "Lcom/xiaomi/micolauncher/module/h0$e;",
]

STUB_FILES: Dict[str, str] = {
    "bk/c.java": """package bk;
import com.xiaomi.micolauncher.feature.appscreen.data.model.AppPageData;
public class c {
    public AppPageData a;
    public c(AppPageData data) { this.a = data; }
}
""",
    "com/xiaomi/micolauncher/feature/appscreen/data/model/CategoryTab.java": """package com.xiaomi.micolauncher.feature.appscreen.data.model;
public class CategoryTab {
    public CategoryTab() {}
    public void setAppTabKey(String key) {}
    public void setAppTabName(String name) {}
    public void setConfigurable(boolean configurable) {}
    public void setModeType(String modeType) {}
    public void setTag(String tag) {}
}
""",
    "com/xiaomi/micolauncher/feature/appscreen/data/model/AppPageData.java": """package com.xiaomi.micolauncher.feature.appscreen.data.model;
import java.util.List;
public class AppPageData {
    public AppPageData() {}
    public void setTabList(List<CategoryTab> tabList) {}
    public void setRecommendCardList(List<?> recommendCardList) {}
}
""",
    "com/xiaomi/mico/appstore/bean/AppInfo.java": """package com.xiaomi.mico.appstore.bean;
public class AppInfo {
    public AppInfo(String iconUrl, String appName, String packageName, String downloadUrl, String micoAction, String appType) {}
    public String getAppName() { return null; }
    public String getAppType() { return null; }
    public String getIconUrl() { return null; }
    public String getMicoAction() { return null; }
    public String getPackageName() { return null; }
    public void setAppKey(long appKey) {}
    public void setModeType(String modeType) {}
    public void setOnlineStatus(String onlineStatus) {}
    public void setRemovable(boolean removable) {}
}
""",
    "iw/a.java": """package iw;
import android.content.Context;
public class a {
    public static Context b() { return null; }
}
""",
    "rg/h.java": """package rg;
import android.widget.ImageView;
public class h {
    public static void d0(ImageView... views) {}
}
""",
}

DEX_TOOL_JAVA = r"""
import com.android.tools.smali.baksmali.Adaptors.ClassDefinition;
import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DexTool {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("Missing command");
        }
        String cmd = args[0];
        if ("disasm".equals(cmd)) {
            File dex = new File(args[1]);
            File outDir = new File(args[2]);
            Set<String> targets = new HashSet<String>(Arrays.asList(Arrays.copyOfRange(args, 3, args.length)));
            DexFile df = DexFileFactory.loadDexFile(dex, Opcodes.forApi(26));
            BaksmaliOptions opts = new BaksmaliOptions();
            opts.apiLevel = 26;
            int found = 0;
            for (ClassDef cd : df.getClasses()) {
                if (targets.contains(cd.getType())) {
                    String rel = cd.getType().substring(1, cd.getType().length() - 1) + ".smali";
                    File outFile = new File(outDir, rel);
                    outFile.getParentFile().mkdirs();
                    try (BaksmaliWriter w = new BaksmaliWriter(new FileWriter(outFile))) {
                        ClassDefinition def = new ClassDefinition(opts, cd);
                        def.writeTo(w);
                    }
                    found++;
                }
            }
            if (found != targets.size()) {
                throw new IllegalStateException("Expected " + targets.size() + " classes, found " + found);
            }
        } else if ("asm".equals(cmd)) {
            File outDex = new File(args[1]);
            SmaliOptions opts = new SmaliOptions();
            opts.apiLevel = 26;
            opts.outputDexFile = outDex.getAbsolutePath();
            List<String> files = Arrays.asList(Arrays.copyOfRange(args, 2, args.length));
            if (!Smali.assemble(opts, files)) {
                throw new IllegalStateException("Smali assemble failed");
            }
        } else if ("merge-replace".equals(cmd)) {
            File baseDex = new File(args[1]);
            File patchDex = new File(args[2]);
            File outDex = new File(args[3]);
            String prefix = args[4];
            Opcodes opcodes = Opcodes.forDexVersion(38);
            DexFile base = DexFileFactory.loadDexFile(baseDex, opcodes);
            DexFile patch = DexFileFactory.loadDexFile(patchDex, opcodes);

            Set<String> replaceTypes = new HashSet<String>();
            List<ClassDef> toAdd = new ArrayList<ClassDef>();
            for (ClassDef cd : patch.getClasses()) {
                if (prefix.isEmpty() || cd.getType().startsWith(prefix)) {
                    replaceTypes.add(cd.getType());
                    toAdd.add(cd);
                }
            }
            if (toAdd.isEmpty()) {
                throw new IllegalStateException("No patch classes matched prefix: " + prefix);
            }
            DexPool pool = new DexPool(opcodes);
            for (ClassDef cd : base.getClasses()) {
                if (!replaceTypes.contains(cd.getType())) {
                    pool.internClass(cd);
                }
            }
            for (ClassDef cd : toAdd) {
                pool.internClass(cd);
            }
            pool.writeTo(new FileDataStore(outDex));
        } else {
            throw new IllegalArgumentException("Unknown command: " + cmd);
        }
    }
}
"""


def _ver_key(name: str) -> Tuple[int, ...]:
    nums = [int(x) for x in re.findall(r"\d+", name)]
    return tuple(nums)


def resolve_java_home() -> Path:
    env_home = os.environ.get("JAVA_HOME")
    if env_home and Path(env_home, "bin/javac").is_file():
        return Path(env_home)
    jdk17 = Path("/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home")
    if (jdk17 / "bin/javac").is_file():
        return jdk17
    which_javac = shutil.which("javac")
    if which_javac:
        return Path(which_javac).resolve().parent.parent
    raise FileNotFoundError("JAVA_HOME not found; set JAVA_HOME to a JDK 17+ installation.")


def resolve_android_sdk() -> Tuple[Path, Path, Path]:
    candidates = [
        os.environ.get("ANDROID_SDK_ROOT"),
        os.environ.get("ANDROID_HOME"),
        str(Path.home() / "Library/Android/sdk"),
        str(Path.home() / "Android/Sdk"),
    ]
    sdk: Optional[Path] = None
    for raw in candidates:
        if raw and Path(raw).is_dir():
            sdk = Path(raw)
            break
    if sdk is None:
        raise FileNotFoundError("Android SDK not found; set ANDROID_SDK_ROOT or ANDROID_HOME.")

    bt_dirs = sorted(
        [p for p in (sdk / "build-tools").iterdir() if p.is_dir() and (p / "apksigner").is_file()],
        key=lambda p: _ver_key(p.name),
    )
    if not bt_dirs:
        raise FileNotFoundError(f"No build-tools found under {sdk / 'build-tools'}")
    build_tools = bt_dirs[-1]

    plat_dirs = sorted(
        [p for p in (sdk / "platforms").iterdir() if p.is_dir() and (p / "android.jar").is_file()],
        key=lambda p: _ver_key(p.name),
    )
    if not plat_dirs:
        raise FileNotFoundError(f"No android.jar found under {sdk / 'platforms'}")
    # Prefer API 30 if present for compatibility, otherwise latest installed.
    android_jar = next((p / "android.jar" for p in plat_dirs if p.name == "android-30"), plat_dirs[-1] / "android.jar")
    return sdk, build_tools, android_jar


def resolve_jadx_jar() -> Path:
    env_jar = os.environ.get("JADX_ALL_JAR")
    if env_jar and Path(env_jar).is_file():
        return Path(env_jar)
    patterns = [
        "/opt/homebrew/Cellar/jadx/*/libexec/lib/jadx-*-all.jar",
        "/usr/local/Cellar/jadx/*/libexec/lib/jadx-*-all.jar",
        "/usr/share/jadx/lib/jadx-*-all.jar",
    ]
    matches: List[Path] = []
    for pat in patterns:
        matches.extend(Path(m) for m in sorted(glob.glob(pat)))
    if matches:
        return matches[-1]
    raise FileNotFoundError("jadx-*-all.jar not found; install jadx or set JADX_ALL_JAR.")


def resolve_adb_bin() -> str:
    return os.environ.get("ADB_BIN") or shutil.which("adb") or "adb"


def run(cmd: List[str], check: bool = True, java_home: Optional[Path] = None) -> str:
    env = dict(os.environ)
    if java_home is not None:
        env["JAVA_HOME"] = str(java_home)
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env)
    if check and proc.returncode != 0:
        raise RuntimeError(f"Command failed ({proc.returncode}): {' '.join(cmd)}\n{proc.stdout}")
    return proc.stdout


def adb_cmd(args: List[str], serial: Optional[str] = None, check: bool = True) -> str:
    adb_bin = resolve_adb_bin()
    base = [adb_bin]
    if serial:
        base.extend(["-s", serial])
    return run(base + args, check=check)


def assert_replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Patch anchor '{label}' matched {count} times (expected 1)")
    return text.replace(old, new, 1)


def get_dex_crcs(apk_path: Path) -> Dict[str, int]:
    crcs: Dict[str, int] = {}
    with zipfile.ZipFile(apk_path, "r") as zf:
        for idx in range(1, 6):
            name = "classes.dex" if idx == 1 else f"classes{idx}.dex"
            crcs[name] = zf.getinfo(name).CRC
    return crcs


def patch_app_home_fragment(smali_path: Path) -> None:
    text = smali_path.read_text(encoding="utf-8")

    # 1. Replace Z() so it immediately loads our local page data synchronously.
    m_z = re.search(
        r"\.method protected Z\(\)V.*?\.end method",
        text,
        flags=re.DOTALL,
    )
    if not m_z or "loadAppsDataFromCache" not in m_z.group(0):
        raise RuntimeError("Unexpected AppHomeFragment.Z() body")
    new_z = (
        ".method protected Z()V\n"
        "    .registers 2\n\n"
        "    const/4 v0, 0x0\n\n"
        "    invoke-virtual {p0, v0}, Lcom/xiaomi/micolauncher/feature/appscreen/fragment/AppHomeFragment;->loadAppsDataSuccess(Lbk/c;)V\n\n"
        "    return-void\n"
        ".end method"
    )
    text = text[: m_z.start()] + new_z + text[m_z.end() :]

    # 2. Replace loadAppsDataSuccess(Lbk/c;) input event creation unconditionally.
    old_load_guard = (
        "    if-nez p1, :cond_9\n\n"
        "    goto :goto_4f\n\n"
        "    :cond_9\n"
        "    iget-object p1, p1, Lbk/c;->a:Lcom/xiaomi/micolauncher/feature/appscreen/data/model/AppPageData;"
    )
    new_load_guard = (
        "    invoke-static {}, Lcom/xiaomi/micolauncher/feature/appscreen/LocalAppsHelper;->buildLocalPageEvent()Lbk/c;\n\n"
        "    move-result-object p1\n\n"
        "    iget-object p1, p1, Lbk/c;->a:Lcom/xiaomi/micolauncher/feature/appscreen/data/model/AppPageData;"
    )
    text = assert_replace_once(text, old_load_guard, new_load_guard, "AppHomeFragment.loadAppsDataSuccess")

    # 3. Replace E0(CategoryTab) SkillDataManager lookup & avoid resetting adapter on resume.
    old_e0_block = (
        "    :cond_2e\n"
        "    invoke-static {}, Lcom/xiaomi/micolauncher/feature/appscreen/data/SkillDataManager;->getManager()Lcom/xiaomi/micolauncher/feature/appscreen/data/SkillDataManager;\n\n"
        "    move-result-object p1\n\n"
        "    invoke-virtual {p1, v1}, Lcom/xiaomi/micolauncher/feature/appscreen/data/SkillDataManager;->getAppListByType(Ljava/lang/String;)Ljava/util/List;\n\n"
        "    move-result-object p1\n\n"
        "    new-instance v1, Landroidx/recyclerview/widget/GridLayoutManager;\n\n"
        "    invoke-virtual {p0}, Lcom/xiaomi/micolauncher/common/ui/BaseFragment;->getContext()Landroid/content/Context;\n\n"
        "    move-result-object v2\n\n"
        "    const/4 v3, 0x3\n\n"
        "    invoke-direct {v1, v2, v3, v0, v0}, Landroidx/recyclerview/widget/GridLayoutManager;-><init>(Landroid/content/Context;IIZ)V\n\n"
        "    iget-object v0, p0, Lcom/xiaomi/micolauncher/feature/appscreen/fragment/AppHomeFragment;->l:Lak/a;\n\n"
        "    iget-object v2, p0, Lcom/xiaomi/micolauncher/feature/appscreen/fragment/AppHomeFragment;->p:Lcom/xiaomi/micolauncher/feature/appscreen/data/model/CategoryTab;\n\n"
        "    invoke-virtual {v0, p1, v2}, Lak/a;->q(Ljava/util/List;Lcom/xiaomi/micolauncher/feature/appscreen/data/model/CategoryTab;)V\n\n"
        "    iget-object p1, p0, Lcom/xiaomi/micolauncher/common/ui/BaseHomeFragment;->a:Landroidx/recyclerview/widget/RecyclerView;\n\n"
        "    invoke-virtual {p1, v1}, Landroidx/recyclerview/widget/RecyclerView;->setLayoutManager(Landroidx/recyclerview/widget/RecyclerView$p;)V\n\n"
        "    iget-object p1, p0, Lcom/xiaomi/micolauncher/common/ui/BaseHomeFragment;->a:Landroidx/recyclerview/widget/RecyclerView;\n\n"
        "    iget-object v0, p0, Lcom/xiaomi/micolauncher/feature/appscreen/fragment/AppHomeFragment;->l:Lak/a;\n\n"
        "    invoke-virtual {p1, v0}, Landroidx/recyclerview/widget/RecyclerView;->setAdapter(Landroidx/recyclerview/widget/RecyclerView$h;)V\n\n"
        "    return-void"
    )
    new_e0_block = (
        "    :cond_2e\n"
        "    invoke-virtual {p0}, Lcom/xiaomi/micolauncher/common/ui/BaseFragment;->getContext()Landroid/content/Context;\n\n"
        "    move-result-object p1\n\n"
        "    invoke-static {p1, v1}, Lcom/xiaomi/micolauncher/feature/appscreen/LocalAppsHelper;->getAppsForTab(Landroid/content/Context;Ljava/lang/String;)Ljava/util/List;\n\n"
        "    move-result-object p1\n\n"
        "    iget-object v1, p0, Lcom/xiaomi/micolauncher/feature/appscreen/fragment/AppHomeFragment;->l:Lak/a;\n\n"
        "    iget-object v2, p0, Lcom/xiaomi/micolauncher/feature/appscreen/fragment/AppHomeFragment;->p:Lcom/xiaomi/micolauncher/feature/appscreen/data/model/CategoryTab;\n\n"
        "    invoke-virtual {v1, p1, v2}, Lak/a;->q(Ljava/util/List;Lcom/xiaomi/micolauncher/feature/appscreen/data/model/CategoryTab;)V\n\n"
        "    iget-object p1, p0, Lcom/xiaomi/micolauncher/common/ui/BaseHomeFragment;->a:Landroidx/recyclerview/widget/RecyclerView;\n\n"
        "    invoke-virtual {p1}, Landroidx/recyclerview/widget/RecyclerView;->getAdapter()Landroidx/recyclerview/widget/RecyclerView$h;\n\n"
        "    move-result-object v1\n\n"
        "    iget-object v2, p0, Lcom/xiaomi/micolauncher/feature/appscreen/fragment/AppHomeFragment;->l:Lak/a;\n\n"
        "    if-eq v1, v2, :cond_local_reuse\n\n"
        "    new-instance v1, Landroidx/recyclerview/widget/GridLayoutManager;\n\n"
        "    invoke-virtual {p0}, Lcom/xiaomi/micolauncher/common/ui/BaseFragment;->getContext()Landroid/content/Context;\n\n"
        "    move-result-object v2\n\n"
        "    const/4 v3, 0x3\n\n"
        "    invoke-direct {v1, v2, v3, v0, v0}, Landroidx/recyclerview/widget/GridLayoutManager;-><init>(Landroid/content/Context;IIZ)V\n\n"
        "    invoke-virtual {p1, v1}, Landroidx/recyclerview/widget/RecyclerView;->setLayoutManager(Landroidx/recyclerview/widget/RecyclerView$p;)V\n\n"
        "    iget-object v0, p0, Lcom/xiaomi/micolauncher/feature/appscreen/fragment/AppHomeFragment;->l:Lak/a;\n\n"
        "    invoke-virtual {p1, v0}, Landroidx/recyclerview/widget/RecyclerView;->setAdapter(Landroidx/recyclerview/widget/RecyclerView$h;)V\n\n"
        "    :cond_local_reuse\n"
        "    return-void"
    )
    text = assert_replace_once(text, old_e0_block, new_e0_block, "AppHomeFragment.E0")

    # 4. Refresh live PackageManager entries on onResume() without hitting cloud SkillDataManager.
    old_resume_block = (
        "    iget-object v0, p0, Lcom/xiaomi/micolauncher/common/ui/BaseHomeFragment;->a:Landroidx/recyclerview/widget/RecyclerView;\n\n"
        "    invoke-virtual {v0}, Landroidx/recyclerview/widget/RecyclerView;->getAdapter()Landroidx/recyclerview/widget/RecyclerView$h;\n\n"
        "    move-result-object v0\n\n"
        "    if-eqz v0, :cond_56\n\n"
        "    iget-object v0, p0, Lcom/xiaomi/micolauncher/common/ui/BaseHomeFragment;->a:Landroidx/recyclerview/widget/RecyclerView;\n\n"
        "    invoke-virtual {v0}, Landroidx/recyclerview/widget/RecyclerView;->getAdapter()Landroidx/recyclerview/widget/RecyclerView$h;\n\n"
        "    move-result-object v0\n\n"
        "    invoke-virtual {v0}, Landroidx/recyclerview/widget/RecyclerView$h;->getItemCount()I\n\n"
        "    move-result v0\n\n"
        "    if-eqz v0, :cond_56\n\n"
        "    iget-object v0, p0, Lcom/xiaomi/micolauncher/common/ui/BaseHomeFragment;->a:Landroidx/recyclerview/widget/RecyclerView;\n\n"
        "    invoke-virtual {v0}, Landroidx/recyclerview/widget/RecyclerView;->getAdapter()Landroidx/recyclerview/widget/RecyclerView$h;\n\n"
        "    move-result-object v0\n\n"
        "    invoke-virtual {v0}, Landroidx/recyclerview/widget/RecyclerView$h;->notifyDataSetChanged()V\n\n"
        "    goto :goto_5d\n\n"
        "    :cond_56\n"
        "    invoke-static {}, Lcom/xiaomi/micolauncher/feature/appscreen/data/SkillDataManager;->getManager()Lcom/xiaomi/micolauncher/feature/appscreen/data/SkillDataManager;\n\n"
        "    move-result-object v0\n\n"
        "    invoke-virtual {v0}, Lcom/xiaomi/micolauncher/feature/appscreen/data/SkillDataManager;->loadAppsData()V"
    )
    new_resume_block = (
        "    const/4 v0, 0x0\n\n"
        "    invoke-virtual {p0, v0}, Lcom/xiaomi/micolauncher/feature/appscreen/fragment/AppHomeFragment;->loadAppsDataSuccess(Lbk/c;)V"
    )
    text = assert_replace_once(text, old_resume_block, new_resume_block, "AppHomeFragment.onResume")

    smali_path.write_text(text, encoding="utf-8")


def patch_viewholder_j(smali_path: Path) -> None:
    text = smali_path.read_text(encoding="utf-8")
    old_head = (
        ".method public l(Lcom/xiaomi/mico/appstore/bean/AppInfo;Ljava/lang/String;)V\n"
        "    .registers 7"
    )
    new_head = (
        ".method public l(Lcom/xiaomi/mico/appstore/bean/AppInfo;Ljava/lang/String;)V\n"
        "    .registers 12"
    )
    text = assert_replace_once(text, old_head, new_head, "viewholder/j.l registers")

    old_anchor = (
        "    sget v2, Lzj/d;->H:I\n\n"
        "    invoke-static {v1, v2}, Lcom/xiaomi/micolauncher/common/ui/h0;->c(Landroid/content/Context;I)I\n\n"
        "    move-result v1"
    )
    new_anchor = (
        "    sget v2, Lzj/d;->H:I\n\n"
        "    invoke-static {v1, v2}, Lcom/xiaomi/micolauncher/common/ui/h0;->c(Landroid/content/Context;I)I\n\n"
        "    move-result v1\n\n"
        "    iget-object v4, p0, Lcom/xiaomi/micolauncher/feature/appscreen/viewholder/m;->c:Landroid/content/Context;\n\n"
        "    move-object/from16 v5, p1\n\n"
        "    iget-object v6, p0, Lcom/xiaomi/micolauncher/feature/appscreen/viewholder/j;->i:Landroid/widget/ImageView;\n\n"
        "    iget-object v7, p0, Lcom/xiaomi/micolauncher/feature/appscreen/viewholder/j;->g:Landroid/widget/TextView;\n\n"
        "    move v8, v1\n\n"
        "    invoke-static/range {v4 .. v8}, Lcom/xiaomi/micolauncher/feature/appscreen/LocalAppsHelper;->bindAppItem(Landroid/content/Context;Lcom/xiaomi/mico/appstore/bean/AppInfo;Landroid/widget/ImageView;Landroid/widget/TextView;I)Z\n\n"
        "    move-result v4\n\n"
        "    if-eqz v4, :cond_local_fallback\n\n"
        "    goto :goto_6b\n\n"
        "    :cond_local_fallback"
    )
    text = assert_replace_once(text, old_anchor, new_anchor, "viewholder/j.l bindAppItem hook")
    smali_path.write_text(text, encoding="utf-8")


def patch_viewholder_m(smali_path: Path) -> None:
    text = smali_path.read_text(encoding="utf-8")
    old_anchor = (
        ".method protected n(Lcom/xiaomi/mico/appstore/bean/AppInfo;Ljava/lang/Runnable;)V\n"
        "    .registers 7\n\n"
        "    invoke-virtual {p1}, Lcom/xiaomi/mico/appstore/bean/AppInfo;->getAppType()Ljava/lang/String;"
    )
    new_anchor = (
        ".method protected n(Lcom/xiaomi/mico/appstore/bean/AppInfo;Ljava/lang/Runnable;)V\n"
        "    .registers 7\n\n"
        "    iget-object v0, p0, Lcom/xiaomi/micolauncher/feature/appscreen/viewholder/m;->c:Landroid/content/Context;\n\n"
        "    invoke-static {v0, p1, p2}, Lcom/xiaomi/micolauncher/feature/appscreen/LocalAppsHelper;->handleClick(Landroid/content/Context;Lcom/xiaomi/mico/appstore/bean/AppInfo;Ljava/lang/Runnable;)Z\n\n"
        "    move-result v0\n\n"
        "    if-eqz v0, :cond_local_orig\n\n"
        "    return-void\n\n"
        "    :cond_local_orig\n"
        "    invoke-virtual {p1}, Lcom/xiaomi/mico/appstore/bean/AppInfo;->getAppType()Ljava/lang/String;"
    )
    text = assert_replace_once(text, old_anchor, new_anchor, "viewholder/m.n handleClick hook")
    smali_path.write_text(text, encoding="utf-8")


def check_dex_stats(dexdump_bin: Path, dex_path: Path) -> Tuple[str, int]:
    out = run([str(dexdump_bin), "-f", str(dex_path)])
    ver = ""
    methods = -1
    for line in out.splitlines():
        if "DEX version" in line:
            m = re.search(r"DEX version '([^']+)'", line)
            if m:
                ver = m.group(1)
        elif line.strip().startswith("method_ids_size"):
            methods = int(line.split(":")[-1].strip())
    return ver, methods


def resolve_stock_apk(stock_apk_arg: Optional[str]) -> Path:
    raw = stock_apk_arg or os.environ.get("MICO_STOCK_APK")
    candidates = [Path(raw)] if raw else [PROJECT_DIR / "stock/base.apk"]
    for p in candidates:
        if p.is_file():
            return p
    raise FileNotFoundError(
        "Stock com.xiaomi.micolauncher base.apk not found. Pass --stock-apk <path> or set MICO_STOCK_APK."
    )


def build_apk(stock_apk: Path, keystore: Path) -> Tuple[Path, Dict[str, int], Dict[str, int]]:
    if not HELPER_JAVA.is_file():
        raise FileNotFoundError(f"Helper source not found: {HELPER_JAVA}")
    if not keystore.is_file():
        raise FileNotFoundError(f"Keystore not found: {keystore}; pass --keystore or set MICO_KEYSTORE.")

    java_home = resolve_java_home()
    java_bin = java_home / "bin/java"
    javac_bin = java_home / "bin/javac"
    _, build_tools, android_jar = resolve_android_sdk()
    jadx_jar = resolve_jadx_jar()
    d8_bin = build_tools / "d8"
    zipalign_bin = build_tools / "zipalign"
    apksigner_bin = build_tools / "apksigner"
    dexdump_bin = build_tools / "dexdump"

    work = Path(tempfile.mkdtemp(prefix="mico_build_"))
    print(f"[build] Workdir: {work}")
    try:
        tool_dir = work / "tool"
        tool_dir.mkdir()
        (tool_dir / "DexTool.java").write_text(DEX_TOOL_JAVA, encoding="utf-8")
        run([str(javac_bin), "-cp", str(jadx_jar), str(tool_dir / "DexTool.java"), "-d", str(tool_dir)], java_home=java_home)
        cp_dextool = f"{tool_dir}:{jadx_jar}"

        stock_dex_dir = work / "stock_dex"
        stock_dex_dir.mkdir()
        stock_crcs = get_dex_crcs(stock_apk)
        with zipfile.ZipFile(stock_apk, "r") as zf:
            for name in ("classes4.dex", "classes5.dex"):
                (stock_dex_dir / name).write_bytes(zf.read(name))

        # 1. Compile LocalAppsHelper + stubs -> d8 -> merge into classes5.dex
        java_src_dir = work / "java_src"
        classes_dir = work / "java_classes"
        d8_out = work / "d8_out"
        java_src_dir.mkdir()
        classes_dir.mkdir()
        d8_out.mkdir()

        for rel, code in STUB_FILES.items():
            p = java_src_dir / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(code, encoding="utf-8")

        helper_dst = java_src_dir / "com/xiaomi/micolauncher/feature/appscreen/LocalAppsHelper.java"
        helper_dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(HELPER_JAVA, helper_dst)

        java_files = [str(p) for p in java_src_dir.rglob("*.java")]
        run(
            [
                str(javac_bin),
                "--release",
                "8",
                "-encoding",
                "UTF-8",
                "-cp",
                str(android_jar),
                "-d",
                str(classes_dir),
            ]
            + java_files,
            java_home=java_home,
        )
        helper_class_files = [
            str(p)
            for p in (classes_dir / "com/xiaomi/micolauncher/feature/appscreen").glob("LocalAppsHelper*.class")
        ]
        if not helper_class_files:
            raise RuntimeError("No LocalAppsHelper*.class generated")
        run(
            [
                str(d8_bin),
                "--min-api",
                "26",
                "--lib",
                str(android_jar),
                "--classpath",
                str(classes_dir),
                "--output",
                str(d8_out),
            ]
            + helper_class_files,
            java_home=java_home,
        )

        patched_dex_dir = work / "patched_dex"
        patched_dex_dir.mkdir()
        run(
            [
                str(java_bin),
                "-cp",
                cp_dextool,
                "DexTool",
                "merge-replace",
                str(stock_dex_dir / "classes5.dex"),
                str(d8_out / "classes.dex"),
                str(patched_dex_dir / "classes5.dex"),
                "Lcom/xiaomi/micolauncher/feature/appscreen/LocalAppsHelper",
            ],
            java_home=java_home,
        )

        # 2. Disassemble target classes from classes4.dex, patch Smali, assemble & merge
        smali_dir = work / "smali4"
        smali_dir.mkdir()
        run(
            [
                str(java_bin),
                "-cp",
                cp_dextool,
                "DexTool",
                "disasm",
                str(stock_dex_dir / "classes4.dex"),
                str(smali_dir),
            ]
            + TARGET_CLASSES_4,
            java_home=java_home,
        )

        smali_app_home = smali_dir / "com/xiaomi/micolauncher/feature/appscreen/fragment/AppHomeFragment.smali"
        smali_vh_j = smali_dir / "com/xiaomi/micolauncher/feature/appscreen/viewholder/j.smali"
        smali_vh_m = smali_dir / "com/xiaomi/micolauncher/feature/appscreen/viewholder/m.smali"
        smali_d_a = smali_dir / "com/xiaomi/micolauncher/module/homepage/d$a.smali"
        smali_h0_e = smali_dir / "com/xiaomi/micolauncher/module/h0$e.smali"
        for sp in (smali_app_home, smali_vh_j, smali_vh_m, smali_d_a, smali_h0_e):
            if not sp.is_file():
                raise FileNotFoundError(f"Disassembled smali missing: {sp}")
            cleaned_lines: List[str] = []
            for raw in sp.read_text(encoding="utf-8").splitlines():
                stripped = raw.strip()
                if stripped.startswith(".line "):
                    continue
                if stripped == "" and cleaned_lines and cleaned_lines[-1] == "":
                    continue
                cleaned_lines.append(raw)
            sp.write_text("\n".join(cleaned_lines) + "\n", encoding="utf-8")

        patch_app_home_fragment(smali_app_home)
        patch_viewholder_j(smali_vh_j)
        patch_viewholder_m(smali_vh_m)
        patch_homepage_d_a(smali_d_a)
        patch_tab_presenter_h0_e(smali_h0_e)

        hooks_dex = work / "hooks4.dex"
        run(
            [
                str(java_bin),
                "-cp",
                cp_dextool,
                "DexTool",
                "asm",
                str(hooks_dex),
                str(smali_app_home),
                str(smali_vh_j),
                str(smali_vh_m),
                str(smali_d_a),
                str(smali_h0_e),
            ],
            java_home=java_home,
        )
        run(
            [
                str(java_bin),
                "-cp",
                cp_dextool,
                "DexTool",
                "merge-replace",
                str(stock_dex_dir / "classes4.dex"),
                str(hooks_dex),
                str(patched_dex_dir / "classes4.dex"),
                "",
            ],
            java_home=java_home,
        )

        ver4, meth4 = check_dex_stats(dexdump_bin, patched_dex_dir / "classes4.dex")
        ver5, meth5 = check_dex_stats(dexdump_bin, patched_dex_dir / "classes5.dex")
        print(f"[build] classes4.dex: version={ver4}, method_ids={meth4}/65536")
        print(f"[build] classes5.dex: version={ver5}, method_ids={meth5}/65536")
        if meth4 >= 65536 or meth5 >= 65536:
            raise RuntimeError(f"DEX method limit exceeded: c4={meth4}, c5={meth5}")

        # 3. Repackage into unsigned APK -> zipalign -> apksigner sign
        raw_apk = work / "patched_raw.apk"
        aligned_apk = work / "patched_aligned.apk"
        new_c4 = (patched_dex_dir / "classes4.dex").read_bytes()
        new_c5 = (patched_dex_dir / "classes5.dex").read_bytes()

        with zipfile.ZipFile(stock_apk, "r") as zin, zipfile.ZipFile(raw_apk, "w") as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                if item.filename == "classes4.dex":
                    data = new_c4
                elif item.filename == "classes5.dex":
                    data = new_c5
                zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
                zi.compress_type = item.compress_type
                zi.external_attr = item.external_attr
                zout.writestr(zi, data, compress_type=item.compress_type)

        run([str(zipalign_bin), "-f", "-p", "4", str(raw_apk), str(aligned_apk)], java_home=java_home)
        OUT_APK.parent.mkdir(parents=True, exist_ok=True)
        run(
            [
                str(apksigner_bin),
                "sign",
                "--v4-signing-enabled",
                "false",
                "--ks",
                str(keystore),
                "--ks-key-alias",
                os.environ.get("MICO_KEY_ALIAS", "androiddebugkey"),
                "--ks-pass",
                os.environ.get("MICO_KS_PASS", "pass:android"),
                "--key-pass",
                os.environ.get("MICO_KEY_PASS", "pass:android"),
                "--out",
                str(OUT_APK),
                str(aligned_apk),
            ],
            java_home=java_home,
        )
        verify_out = run([str(apksigner_bin), "verify", "--verbose", str(OUT_APK)], java_home=java_home)
        print(f"[build] Signed APK: {OUT_APK} ({OUT_APK.stat().st_size} bytes)")
        for line in verify_out.splitlines():
            if "Verified" in line or "Signer" in line or "digest" in line:
                print(f"[build]   {line}")

        return OUT_APK, stock_crcs, get_dex_crcs(OUT_APK)
    finally:
        shutil.rmtree(work, ignore_errors=True)


def patch_homepage_d_a(smali_path: Path) -> None:
    text = smali_path.read_text(encoding="utf-8")
    old_anchor = (
        "    const-class v0, Lij/a;\n\n"
        "    invoke-static {v0}, Lqd/c;->a(Ljava/lang/Class;)Lqd/a;\n\n"
        "    move-result-object v0\n\n"
        "    check-cast v0, Lij/a;\n\n"
        "    invoke-interface {v0}, Lij/g;->L1()Ljava/lang/Class;\n\n"
        "    move-result-object v0\n\n"
        "    iput-object v0, p0, Lcom/xiaomi/micolauncher/module/homepage/d$a;->b:Ljava/lang/Class;"
    )
    new_anchor = (
        "    const-class v0, Lcom/xiaomi/micolauncher/feature/appscreen/fragment/AppHomeFragment;\n\n"
        "    iput-object v0, p0, Lcom/xiaomi/micolauncher/module/homepage/d$a;->b:Ljava/lang/Class;"
    )
    text = assert_replace_once(text, old_anchor, new_anchor, "homepage/d$a APP_TAB fragment")
    smali_path.write_text(text, encoding="utf-8")


def patch_tab_presenter_h0_e(smali_path: Path) -> None:
    text = smali_path.read_text(encoding="utf-8")
    old_anchor = (
        '    const-string v3, "aibox_screen_app"\n\n'
        "    const-class v4, Lcom/xiaomi/business/fragment/BlankAppFragment;"
    )
    new_anchor = (
        '    const-string v3, "aibox_screen_app"\n\n'
        "    const-class v4, Lcom/xiaomi/micolauncher/feature/appscreen/fragment/AppHomeFragment;"
    )
    text = assert_replace_once(text, old_anchor, new_anchor, "h0$e epgTab aibox_screen_app")
    smali_path.write_text(text, encoding="utf-8")


def patch_oat_and_vdex_crcs(
    odex_path: Path,
    vdex_path: Path,
    patched_crcs: Dict[str, int],
    stock_crcs: Dict[str, int],
) -> None:
    dex_names = ["classes.dex", "classes2.dex", "classes3.dex", "classes4.dex", "classes5.dex"]
    old_list = [patched_crcs[n] for n in dex_names]
    new_list = [stock_crcs[n] for n in dex_names]

    # 1. Patch vdex019 location_checksums_ at 0x14..0x27
    vdex = bytearray(vdex_path.read_bytes())
    if vdex[:8] != b"vdex019\x00":
        raise RuntimeError(f"Unexpected VDEX header: {vdex[:12]!r}")
    dex_count = struct.unpack_from("<I", vdex, 0x0C)[0]
    if dex_count != 5:
        raise RuntimeError(f"Expected 5 DEX files in VDEX, got {dex_count}")
    for i in range(5):
        cur = struct.unpack_from("<I", vdex, 0x14 + i * 4)[0]
        if cur != old_list[i]:
            raise RuntimeError(
                f"VDEX CRC mismatch at index {i} ({dex_names[i]}): got 0x{cur:08x}, expected 0x{old_list[i]:08x}"
            )
        struct.pack_into("<I", vdex, 0x14 + i * 4, new_list[i])
    vdex_path.write_bytes(vdex)
    print("[deploy] Patched base.vdex location CRC32s back to stock")

    # 2. Patch oat 138 OatDexFile dex_file_location_checksum_ entries in base.odex
    odex = bytearray(odex_path.read_bytes())
    oat_off = odex.find(b"oat\n138\x00")
    if oat_off == -1:
        raise RuntimeError("oat 138 magic not found in base.odex")
    oat_dex_count = struct.unpack_from("<I", odex, oat_off + 0x14)[0]
    if oat_dex_count != 5:
        raise RuntimeError(f"Expected 5 OatDexFiles, got {oat_dex_count}")

    loc_strings = [
        b"base.apk",
        b"base.apk!classes2.dex",
        b"base.apk!classes3.dex",
        b"base.apk!classes4.dex",
        b"base.apk!classes5.dex",
    ]
    oat_dex_off = oat_off + struct.unpack_from("<I", odex, oat_off + 0x18)[0]
    search_start = oat_dex_off
    for i, loc in enumerate(loc_strings):
        needle = struct.pack("<I", len(loc)) + loc
        hit = odex.find(needle, search_start)
        if hit == -1:
            raise RuntimeError(f"OatDexFile location {loc!r} not found after 0x{search_start:x}")
        crc_off = hit + len(needle)
        cur = struct.unpack_from("<I", odex, crc_off)[0]
        if cur != old_list[i]:
            raise RuntimeError(
                f"ODEX CRC mismatch for {loc!r} at 0x{crc_off:x}: got 0x{cur:08x}, expected 0x{old_list[i]:08x}"
            )
        struct.pack_into("<I", odex, crc_off, new_list[i])
        search_start = crc_off + 4
    odex_path.write_bytes(odex)
    print("[deploy] Patched base.odex OatDexFile CRC32s back to stock")


def resolve_remote_app_dir(serial: Optional[str]) -> Tuple[str, str]:
    out = adb_cmd(
        [
            "shell",
            "runcon u:r:shell:s0 pm path com.xiaomi.micolauncher 2>/dev/null || pm path com.xiaomi.micolauncher",
        ],
        serial=serial,
        check=False,
    )
    m = re.search(r"package:(/data/app/com\.xiaomi\.micolauncher-[^/\r\n]+)/base\.apk", out)
    if m:
        app_dir = m.group(1)
        return app_dir, f"{app_dir}/base.apk"

    ls_out = adb_cmd(["shell", "ls -d /data/app/com.xiaomi.micolauncher-* 2>/dev/null | head -n 1"], serial=serial, check=False).strip()
    if ls_out.startswith("/data/app/com.xiaomi.micolauncher-"):
        return ls_out, f"{ls_out}/base.apk"
    raise RuntimeError(f"Could not locate /data/app/com.xiaomi.micolauncher-* directory on device (pm path output: {out!r})")


def deploy_to_x08e(
    apk_path: Path,
    stock_crcs: Optional[Dict[str, int]],
    patched_crcs: Dict[str, int],
    serial: Optional[str] = None,
) -> None:
    target_desc = serial or "default ADB target"
    print(f"[deploy] Resolving com.xiaomi.micolauncher path on X08E ({target_desc})...")
    remote_app_dir, remote_base_apk = resolve_remote_app_dir(serial)
    remote_oat_dir = f"{remote_app_dir}/oat/arm"
    print(f"[deploy] Remote app dir: {remote_app_dir}")

    temp_stock_dir: Optional[Path] = None
    try:
        if stock_crcs is None:
            temp_stock_dir = Path(tempfile.mkdtemp(prefix="mico_stock_"))
            pulled_stock = temp_stock_dir / "stock_base.apk"
            print("[deploy] Pulling device stock base.apk to read DEX CRC32s...")
            adb_cmd(["pull", remote_base_apk, str(pulled_stock)], serial=serial)
            stock_crcs = get_dex_crcs(pulled_stock)

        # Backup stock oat/arm once if absent
        adb_cmd(
            [
                "shell",
                f"if [ ! -d {REMOTE_STOCK_BACKUP} ]; then "
                f"mkdir -p {REMOTE_STOCK_BACKUP} && cp -a {remote_oat_dir}/* {REMOTE_STOCK_BACKUP}/; "
                f"fi; rm -rf {REMOTE_WORK_DIR} && mkdir -p {REMOTE_WORK_DIR}",
            ],
            serial=serial,
        )

        adb_cmd(["push", str(apk_path), f"{REMOTE_WORK_DIR}/base.apk"], serial=serial)

        dex2oat_cmd = (
            f"/system/bin/dex2oat "
            f"--dex-file={REMOTE_WORK_DIR}/base.apk "
            f"--dex-location=base.apk "
            f"--oat-file={REMOTE_WORK_DIR}/base.odex "
            f"--output-vdex={REMOTE_WORK_DIR}/base.vdex "
            f"--instruction-set=arm "
            f"--instruction-set-variant=cortex-a53 "
            f"--instruction-set-features=default "
            f"--runtime-arg -Xms64m "
            f"--runtime-arg -Xmx512m "
            f"--compiler-filter=quicken "
            f"--classpath-dir={remote_app_dir} "
            f"--class-loader-context='{REMOTE_CLC}' "
            f"--compact-dex-level=none "
            f"--runtime-arg -Xtarget-sdk-version:27 "
            f"--compilation-reason=install"
        )
        print("[deploy] Running dex2oat --compiler-filter=quicken on X08E...")
        out = adb_cmd(["shell", dex2oat_cmd], serial=serial)
        if out.strip():
            print(out.strip())

        OUT_OAT_DIR.mkdir(parents=True, exist_ok=True)
        local_odex = OUT_OAT_DIR / "base.odex"
        local_vdex = OUT_OAT_DIR / "base.vdex"
        adb_cmd(["pull", f"{REMOTE_WORK_DIR}/base.odex", str(local_odex)], serial=serial)
        adb_cmd(["pull", f"{REMOTE_WORK_DIR}/base.vdex", str(local_vdex)], serial=serial)

        patch_oat_and_vdex_crcs(local_odex, local_vdex, patched_crcs, stock_crcs)

        adb_cmd(["push", str(local_odex), f"{REMOTE_WORK_DIR}/patched.odex"], serial=serial)
        adb_cmd(["push", str(local_vdex), f"{REMOTE_WORK_DIR}/patched.vdex"], serial=serial)

        install_cmd = (
            f"OWNER=$(stat -c '%u:%g' {remote_oat_dir}/base.odex 2>/dev/null || echo system:all_a33) && "
            f"runcon u:r:shell:s0 am force-stop com.xiaomi.micolauncher && "
            f"(killall -9 com.xiaomi.micolauncher com.xiaomi.micolauncher:miot 2>/dev/null || true) && "
            f"rm -f {remote_oat_dir}/base.art && "
            f"rm -rf /data/data/com.xiaomi.micolauncher/code_cache/* && "
            f"cp {REMOTE_WORK_DIR}/patched.odex {remote_oat_dir}/base.odex && "
            f"cp {REMOTE_WORK_DIR}/patched.vdex {remote_oat_dir}/base.vdex && "
            f"chown \"$OWNER\" {remote_oat_dir}/base.odex {remote_oat_dir}/base.vdex && "
            f"chmod 0644 {remote_oat_dir}/base.odex {remote_oat_dir}/base.vdex && "
            f"restorecon -R {remote_oat_dir} && "
            f"rm -rf {REMOTE_WORK_DIR} && "
            f"runcon u:r:shell:s0 am start -n com.xiaomi.micolauncher/.Launcher --es epgTab aibox_screen_app"
        )
        print("[deploy] Installing oat/arm artifacts & restarting com.xiaomi.micolauncher...")
        inst_out = adb_cmd(["shell", install_cmd], serial=serial)
        if inst_out.strip():
            print(inst_out.strip())
    finally:
        if temp_stock_dir is not None:
            shutil.rmtree(temp_stock_dir, ignore_errors=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="Build and optionally deploy X08E MicoLauncher patch.")
    parser.add_argument("--deploy", action="store_true", help="Compile oat/arm on X08E and deploy after building.")
    parser.add_argument(
        "--deploy-apk",
        metavar="APK_PATH",
        help="Deploy a prebuilt patched APK (via dex2oat + oat/arm CRC32 alignment) without rebuilding.",
    )
    parser.add_argument(
        "--stock-apk",
        metavar="APK_PATH",
        help="Stock com.xiaomi.micolauncher base.apk path (or set MICO_STOCK_APK).",
    )
    parser.add_argument(
        "--keystore",
        metavar="KEYSTORE_PATH",
        default=os.environ.get("MICO_KEYSTORE", str(Path.home() / ".android/debug.keystore")),
        help="Android signing keystore for building standalone APK.",
    )
    parser.add_argument(
        "--serial",
        metavar="SERIAL",
        default=os.environ.get("X08E_SERIAL"),
        help="ADB target serial or host:port (or set X08E_SERIAL).",
    )
    args = parser.parse_args()

    if args.deploy_apk:
        apk_path = Path(args.deploy_apk)
        if not apk_path.is_file():
            raise FileNotFoundError(f"Patched APK not found: {apk_path}")
        stock_crcs = get_dex_crcs(Path(args.stock_apk)) if args.stock_apk else None
        deploy_to_x08e(apk_path, stock_crcs, get_dex_crcs(apk_path), serial=args.serial)
        return

    stock_apk = resolve_stock_apk(args.stock_apk)
    apk_path, stock_crcs, patched_crcs = build_apk(stock_apk, Path(args.keystore))
    if args.deploy:
        deploy_to_x08e(apk_path, stock_crcs, patched_crcs, serial=args.serial)


if __name__ == "__main__":
    main()
