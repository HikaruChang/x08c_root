package com.xiaomi.micolauncher.feature.appscreen;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.xiaomi.mico.appstore.bean.AppInfo;
import com.xiaomi.micolauncher.feature.appscreen.data.model.AppPageData;
import com.xiaomi.micolauncher.feature.appscreen.data.model.CategoryTab;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Replaces the cloud-driven AppHomeFragment data & binding layer on Redmi X08E
 * so the native MicoLauncher Apps page displays all installed local apps and
 * verified built-in Xiaomi UI features without AppStore promos.
 */
public final class LocalAppsHelper {

    public static final String COPYRIGHT = "Powered by NyphexAI. Developed by Hikaru(i@rua.moe)";

    private static final String TAG = "MicoLocalApps";
    private static final String APP_TYPE_LOCAL_APK = "local_apk";
    private static final String APP_TYPE_SYSTEM_APP = "system_app";
    private static final String SCHEME_COMPONENT = "component://";

    private static final String TAB_KEY_ALL = "local_all";
    private static final String TAB_KEY_APPS = "local_apps";
    private static final String TAB_KEY_SKILLS = "local_skills";

    private static final String PKG_LAUNCHER = "com.xiaomi.micolauncher";
    private static final String PKG_TRACEUR = "com.android.traceur";
    private static final String PKG_VOIP = "com.xiaomi.micovoip";
    private static final String ACT_VOIP_TEST = "com.xiaomi.micovoip.test.TestAc";
    private static final String PKG_GALLERY = "com.xiaomi.mico.gallery";
    private static final String PKG_SETTINGS = "com.xiaomi.mico.settings";

    private static final Set<String> HIDDEN_PACKAGES = Collections.unmodifiableSet(
        new HashSet<String>(Arrays.asList(
            PKG_LAUNCHER,
            PKG_TRACEUR,
            "com.xiaomi.mico.appstore",
            PKG_GALLERY,
            PKG_SETTINGS,
            "com.dancetv.bokecc.sqaredancetv",
            "com.sinyee.babybus.recommendapp"
        ))
    );

    private static final int ICON_PROBE_SIZE_PX = 32;

    private static final int TILE_BG_COLOR = 0xFF252C37;
    private static final int ICON_CACHE_MAX_BYTES = 4 * 1024 * 1024;

    private static final class IconCache extends LruCache<String, Bitmap> {
        IconCache(int maxSize) {
            super(maxSize);
        }

        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : value.getByteCount();
        }
    }

    private static final LruCache<String, Bitmap> ICON_CACHE = new IconCache(ICON_CACHE_MAX_BYTES);

    private static final class BuiltinSkillSpec {
        final long appKey;
        final String name;
        final String iconUrl;
        final String micoAction;
        final String duplicatePackage;

        BuiltinSkillSpec(long appKey, String name, String iconUrl, String micoAction, String duplicatePackage) {
            this.appKey = appKey;
            this.name = name;
            this.iconUrl = iconUrl;
            this.micoAction = micoAction;
            this.duplicatePackage = duplicatePackage;
        }
    }

    private static final BuiltinSkillSpec[] BUILTIN_SKILLS = new BuiltinSkillSpec[] {
        new BuiltinSkillSpec(
            1606289975L,
            "天气",
            "https://cdn.cnbj1.fds.api.mi-img.com/mico/bb06d17a-2898-4957-a9a5-6894e83c39f2",
            "mico://launcher/weather",
            null
        ),
        new BuiltinSkillSpec(
            30L,
            "闹钟",
            "https://cdn.cnbj1.fds.api.mi-img.com/mico/b6152438-fbbe-4c00-86e3-1e5150f85a5f",
            "mico://launcher/alarm",
            null
        ),
        new BuiltinSkillSpec(
            1615198011L,
            "整点报时",
            "https://cdn.cnbj1.fds.api.mi-img.com/mico/d7525b44-3e81-4de8-b649-77b02882d08a",
            "mico://launcher/hourly_chime",
            null
        ),
        new BuiltinSkillSpec(
            24L,
            "白噪音",
            "https://cdn.cnbj1.fds.api.mi-img.com/mico/870b2bd4-93bf-499d-aad5-9d7910e6eee8",
            "mico://launcher/white_noise",
            null
        ),
        new BuiltinSkillSpec(
            1644374529L,
            "家庭通话",
            "https://cdn.cnbj1.fds.api.mi-img.com/mico/2597a024-3df5-4efd-99b7-ec6dff2dd1e7",
            "mico://launcher/ims/contacts",
            null
        ),
        new BuiltinSkillSpec(
            1627368401L,
            "家庭传声",
            "https://cdn.cnbj1.fds.api.mi-img.com/mico/7a4fecec-0ce2-41fa-b9e4-684b4b6978d8",
            "mico://launcher/intercom",
            null
        ),
        new BuiltinSkillSpec(
            25L,
            "家庭相册",
            "https://cdn.cnbj1.fds.api.mi-img.com/mico/5da7a51d-9d3f-4998-94a9-7845f7886c40",
            "mico://launcher/received_images_album",
            PKG_GALLERY
        ),
        new BuiltinSkillSpec(
            28L,
            "设置",
            "https://cdn.cnbj1.fds.api.mi-img.com/mico/33de0d28-7a55-4839-887a-6e4e62857da6",
            "mico://launcher/setup",
            PKG_SETTINGS
        )
    };

    private static final class LocalEntry {
        final AppInfo appInfo;
        final boolean isSystemApp;
        final int priority;
        final String label;

        LocalEntry(AppInfo appInfo, boolean isSystemApp, int priority, String label) {
            this.appInfo = appInfo;
            this.isSystemApp = isSystemApp;
            this.priority = priority;
            this.label = label;
        }
    }

    private static final class EntryComparator implements Comparator<LocalEntry> {
        private final Collator collator = Collator.getInstance(Locale.CHINA);

        EntryComparator() {
        }

        @Override
        public int compare(LocalEntry a, LocalEntry b) {
            if (a.priority != b.priority) {
                return a.priority < b.priority ? -1 : 1;
            }
            if (a.isSystemApp != b.isSystemApp) {
                return a.isSystemApp ? 1 : -1;
            }
            int cmp = collator.compare(a.label, b.label);
            if (cmp != 0) {
                return cmp;
            }
            return a.appInfo.getPackageName().compareTo(b.appInfo.getPackageName());
        }
    }

    private LocalAppsHelper() {
    }

    /**
     * Builds the synthetic AppsDataLoadSuccess event with local category tabs.
     */
    public static bk.c buildLocalPageEvent() {
        List<CategoryTab> tabs = new ArrayList<CategoryTab>(3);
        tabs.add(makeTab(TAB_KEY_ALL, "全部"));
        tabs.add(makeTab(TAB_KEY_APPS, "本机应用"));
        tabs.add(makeTab(TAB_KEY_SKILLS, "内置功能"));

        AppPageData pageData = new AppPageData();
        pageData.setTabList(tabs);
        pageData.setRecommendCardList(Collections.<Object>emptyList());
        return new bk.c(pageData);
    }

    private static CategoryTab makeTab(String key, String name) {
        CategoryTab tab = new CategoryTab();
        tab.setAppTabKey(key);
        tab.setAppTabName(name);
        tab.setConfigurable(false);
        tab.setModeType("GENERAL");
        tab.setTag(key);
        return tab;
    }

    /**
     * Returns the AppInfo items for the selected local category tab.
     */
    public static List<AppInfo> getAppsForTab(Context context, String tabKey) {
        Context ctx = context != null ? context : iw.a.b();
        if (TAB_KEY_SKILLS.equals(tabKey)) {
            return loadBuiltinSkills(false, Collections.<String>emptySet());
        }
        List<AppInfo> localApks = ctx != null ? loadLocalApks(ctx) : new ArrayList<AppInfo>();
        if (TAB_KEY_APPS.equals(tabKey)) {
            return localApks;
        }
        Set<String> installedPackages = new HashSet<String>();
        for (int i = 0; i < localApks.size(); i++) {
            String pkg = localApks.get(i).getPackageName();
            if (!TextUtils.isEmpty(pkg)) {
                installedPackages.add(pkg);
            }
        }
        List<AppInfo> merged = new ArrayList<AppInfo>(localApks.size() + BUILTIN_SKILLS.length);
        merged.addAll(localApks);
        merged.addAll(loadBuiltinSkills(true, installedPackages));
        return merged;
    }

    private static List<AppInfo> loadBuiltinSkills(boolean dedupeWithLocal, Set<String> installedPackages) {
        List<AppInfo> result = new ArrayList<AppInfo>(BUILTIN_SKILLS.length);
        for (int i = 0; i < BUILTIN_SKILLS.length; i++) {
            BuiltinSkillSpec spec = BUILTIN_SKILLS[i];
            if (dedupeWithLocal && spec.duplicatePackage != null && installedPackages.contains(spec.duplicatePackage)) {
                continue;
            }
            AppInfo info = new AppInfo(spec.iconUrl, spec.name, "", "", spec.micoAction, APP_TYPE_SYSTEM_APP);
            info.setAppKey(spec.appKey);
            info.setModeType("GENERAL");
            info.setOnlineStatus("online");
            info.setRemovable(false);
            result.add(info);
        }
        return result;
    }

    private static List<AppInfo> loadLocalApks(Context context) {
        PackageManager pm = context.getPackageManager();
        if (pm == null) {
            return Collections.emptyList();
        }

        List<ResolveInfo> candidates = new ArrayList<ResolveInfo>();
        queryLauncherActivities(pm, Intent.CATEGORY_LAUNCHER, candidates);
        queryLauncherActivities(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, candidates);

        Set<String> seenPackages = new HashSet<String>();
        List<LocalEntry> entries = new ArrayList<LocalEntry>();

        Drawable defaultActivityIcon = pm.getDefaultActivityIcon();
        Bitmap defaultIconBmp = defaultActivityIcon != null ? renderSmallProbeBitmap(defaultActivityIcon) : null;
        try {
            for (int i = 0; i < candidates.size(); i++) {
                ResolveInfo ri = candidates.get(i);
                ActivityInfo ai = ri.activityInfo;
                if (ai == null || !ai.exported) {
                    continue;
                }
                String pkg = ai.packageName;
                String cls = ai.name;
                if (TextUtils.isEmpty(pkg) || TextUtils.isEmpty(cls)) {
                    continue;
                }
                if (HIDDEN_PACKAGES.contains(pkg)) {
                    continue;
                }
                if (PKG_VOIP.equals(pkg) && (ACT_VOIP_TEST.equals(cls) || cls.endsWith(".TestAc"))) {
                    continue;
                }
                if (seenPackages.contains(pkg)) {
                    continue;
                }
                if (!hasCustomIcon(pm, ri, ai, defaultIconBmp)) {
                    continue;
                }
                seenPackages.add(pkg);

                CharSequence rawLabel = ri.loadLabel(pm);
                String label = !TextUtils.isEmpty(rawLabel) ? rawLabel.toString().trim() : pkg;
                String componentStr = pkg + "/" + cls;

                AppInfo info = new AppInfo("", label, pkg, "", SCHEME_COMPONENT + componentStr, APP_TYPE_LOCAL_APK);
                info.setAppKey(computeStableKey(componentStr));
                info.setModeType("GENERAL");
                info.setOnlineStatus("online");
                info.setRemovable(false);

                boolean isSystemApp = ai.applicationInfo != null
                    && (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                entries.add(new LocalEntry(info, isSystemApp, resolvePriority(pkg, isSystemApp), label));
            }
        } finally {
            if (defaultIconBmp != null && !defaultIconBmp.isRecycled()) {
                defaultIconBmp.recycle();
            }
        }

        Collections.sort(entries, new EntryComparator());

        List<AppInfo> result = new ArrayList<AppInfo>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            result.add(entries.get(i).appInfo);
        }
        return result;
    }

    private static boolean hasCustomIcon(
        PackageManager pm,
        ResolveInfo ri,
        ActivityInfo ai,
        Bitmap defaultIconBmp
    ) {
        if (ai.getIconResource() == 0) {
            return false;
        }
        Drawable icon = ri.loadIcon(pm);
        if (icon == null) {
            return false;
        }
        if (defaultIconBmp == null) {
            return true;
        }
        Bitmap candidateBmp = renderSmallProbeBitmap(icon);
        try {
            return !candidateBmp.sameAs(defaultIconBmp);
        } finally {
            if (!candidateBmp.isRecycled()) {
                candidateBmp.recycle();
            }
        }
    }

    private static Bitmap renderSmallProbeBitmap(Drawable drawable) {
        Bitmap bmp = Bitmap.createBitmap(ICON_PROBE_SIZE_PX, ICON_PROBE_SIZE_PX, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        drawable.setBounds(0, 0, ICON_PROBE_SIZE_PX, ICON_PROBE_SIZE_PX);
        drawable.draw(canvas);
        return bmp;
    }

    private static void queryLauncherActivities(PackageManager pm, String category, List<ResolveInfo> out) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(category);
        List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
        if (list != null) {
            out.addAll(list);
        }
    }

    private static int resolvePriority(String pkg, boolean isSystemApp) {
        if ("rua.moe.shindo".equals(pkg)) {
            return 0;
        }
        if ("moe.rua.x08croot".equals(pkg)) {
            return 1;
        }
        return isSystemApp ? 3 : 2;
    }

    private static long computeStableKey(String value) {
        long h = 0x811c9dc5L;
        for (int i = 0; i < value.length(); i++) {
            h ^= value.charAt(i);
            h *= 0x01000193L;
        }
        return (h & 0x7FFFFFFFFFFFFFFFL) | 0x4000000000000000L;
    }

    /**
     * Binds local APK icons and titles synchronously with rounded corners matching MicoUI.
     * Returns true if handled completely; false delegates to stock Mico icon/title logic.
     */
    public static boolean bindAppItem(
        Context context,
        AppInfo appInfo,
        ImageView iconView,
        TextView titleView,
        int cornerRadiusPx
    ) {
        if (appInfo == null || !APP_TYPE_LOCAL_APK.equals(appInfo.getAppType())) {
            return false;
        }

        if (iconView != null) {
            rg.h.d0(iconView);
        }
        if (titleView != null) {
            String name = appInfo.getAppName();
            titleView.setText(name != null ? name : "");
        }
        if (iconView == null) {
            return true;
        }

        Context ctx = context != null ? context : iconView.getContext();
        if (ctx == null) {
            ctx = iw.a.b();
        }
        if (ctx == null) {
            return true;
        }

        int sizePx = resolveIconSizePx(ctx, iconView, cornerRadiusPx);
        String action = appInfo.getMicoAction();
        String pkg = appInfo.getPackageName();
        String cacheKey = (!TextUtils.isEmpty(action) ? action : String.valueOf(pkg)) + ":" + sizePx + ":" + cornerRadiusPx;

        Bitmap cached = ICON_CACHE.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            iconView.setImageBitmap(cached);
            return true;
        }

        Drawable iconDrawable = loadApkDrawable(ctx, action, pkg);
        if (iconDrawable != null) {
            Bitmap rendered = renderRoundedIcon(iconDrawable, sizePx, cornerRadiusPx);
            if (rendered != null) {
                ICON_CACHE.put(cacheKey, rendered);
                iconView.setImageBitmap(rendered);
            }
        }
        return true;
    }

    private static int resolveIconSizePx(Context ctx, ImageView iconView, int cornerRadiusPx) {
        int measured = Math.max(iconView.getWidth(), iconView.getMeasuredWidth());
        if (measured > 0) {
            return measured;
        }
        if (iconView.getLayoutParams() != null && iconView.getLayoutParams().width > 0) {
            return iconView.getLayoutParams().width;
        }
        int resId = ctx.getResources().getIdentifier("app_icon_size", "dimen", ctx.getPackageName());
        if (resId != 0) {
            int dimen = ctx.getResources().getDimensionPixelSize(resId);
            if (dimen > 0) {
                return dimen;
            }
        }
        return Math.max(cornerRadiusPx * 5, Math.round(80f * ctx.getResources().getDisplayMetrics().density));
    }

    private static Drawable loadApkDrawable(Context ctx, String action, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        if (pm == null) {
            return null;
        }
        ComponentName cn = parseComponent(action);
        if (cn != null) {
            try {
                return pm.getActivityIcon(cn);
            } catch (Throwable ignored) {
            }
        }
        if (!TextUtils.isEmpty(pkg)) {
            try {
                return pm.getApplicationIcon(pkg);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Bitmap renderRoundedIcon(Drawable drawable, int sizePx, int cornerRadiusPx) {
        if (sizePx <= 0) {
            return null;
        }
        boolean isAdaptiveWithBg = Build.VERSION.SDK_INT >= 26
            && (drawable instanceof AdaptiveIconDrawable)
            && ((AdaptiveIconDrawable) drawable).getBackground() != null;

        int insetPx = 0;
        boolean needBackdrop = true;
        if (!isAdaptiveWithBg) {
            float[] metrics = inspectDrawablePerimeter(drawable);
            boolean cornersOpaque = metrics[0] >= 0.75f;
            boolean midEdgesOpaque = metrics[1] >= 0.75f;
            boolean innerRingOpaque = metrics[2] >= 0.50f;
            if (cornersOpaque && midEdgesOpaque) {
                needBackdrop = false;
                insetPx = 0;
            } else if (midEdgesOpaque && innerRingOpaque) {
                // Pre-rounded opaque square icon (e.g. MIUI icons with rounded transparent corners)
                needBackdrop = false;
                insetPx = 0;
            } else if (midEdgesOpaque) {
                // Edge-to-edge circular icon touching the sides
                needBackdrop = true;
                insetPx = Math.round(sizePx * 0.10f);
            } else {
                // Legacy transparent/padded icon
                needBackdrop = true;
                insetPx = Math.round(sizePx * 0.08f);
            }
        }

        Bitmap src = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas srcCanvas = new Canvas(src);
        if (needBackdrop) {
            srcCanvas.drawColor(TILE_BG_COLOR);
        }

        int avail = Math.max(1, sizePx - insetPx * 2);
        int iw = drawable.getIntrinsicWidth();
        int ih = drawable.getIntrinsicHeight();
        int left = insetPx;
        int top = insetPx;
        int right = sizePx - insetPx;
        int bottom = sizePx - insetPx;
        if (!isAdaptiveWithBg && iw > 0 && ih > 0 && iw != ih) {
            float scale = Math.min((float) avail / (float) iw, (float) avail / (float) ih);
            int dw = Math.max(1, Math.round(iw * scale));
            int dh = Math.max(1, Math.round(ih * scale));
            left = (sizePx - dw) / 2;
            top = (sizePx - dh) / 2;
            right = left + dw;
            bottom = top + dh;
        }
        drawable.setBounds(left, top, right, bottom);
        drawable.draw(srcCanvas);

        Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas outCanvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setShader(new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float r = Math.min(cornerRadiusPx, sizePx * 0.5f);
        outCanvas.drawRoundRect(new RectF(0f, 0f, sizePx, sizePx), r, r, paint);
        src.recycle();
        return out;
    }

    private static float[] inspectDrawablePerimeter(Drawable drawable) {
        final int s = 64;
        Bitmap probe = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(probe);
        drawable.setBounds(0, 0, s, s);
        drawable.draw(canvas);

        int[][] cornerPts = new int[][] { {3, 3}, {s - 4, 3}, {3, s - 4}, {s - 4, s - 4} };
        int[][] edgePts = new int[][] { {s / 2, 3}, {3, s / 2}, {s - 4, s / 2}, {s / 2, s - 4} };
        int[][] innerPts = new int[][] { {7, 7}, {s - 8, 7}, {7, s - 8}, {s - 8, s - 8} };

        float corners = opaqueFraction(probe, cornerPts);
        float edges = opaqueFraction(probe, edgePts);
        float inner = opaqueFraction(probe, innerPts);
        probe.recycle();
        return new float[] { corners, edges, inner };
    }

    private static float opaqueFraction(Bitmap bmp, int[][] pts) {
        int hits = 0;
        for (int i = 0; i < pts.length; i++) {
            int alpha = Color.alpha(bmp.getPixel(pts[i][0], pts[i][1]));
            if (alpha >= 200) {
                hits++;
            }
        }
        return (float) hits / (float) pts.length;
    }

    /**
     * Handles clicks on local APK entries by launching their resolved component directly.
     * Returns false for non-local entries so SchemaManager handles built-in Xiaomi UI routes.
     */
    public static boolean handleClick(Context context, AppInfo appInfo, Runnable callback) {
        if (appInfo == null || !APP_TYPE_LOCAL_APK.equals(appInfo.getAppType())) {
            return false;
        }

        Context ctx = context != null ? context : iw.a.b();
        if (ctx == null) {
            return true;
        }

        boolean launched = false;
        ComponentName cn = parseComponent(appInfo.getMicoAction());
        if (cn != null) {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(cn);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                ctx.startActivity(intent);
                launched = true;
            } catch (Throwable t) {
                Log.w(TAG, "Direct component launch failed for " + cn.flattenToShortString(), t);
            }
        }

        String pkg = appInfo.getPackageName();
        PackageManager pm = ctx.getPackageManager();
        if (!launched && !TextUtils.isEmpty(pkg) && pm != null) {
            Intent fallback = pm.getLaunchIntentForPackage(pkg);
            if (fallback == null) {
                fallback = pm.getLeanbackLaunchIntentForPackage(pkg);
            }
            if (fallback != null) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                try {
                    ctx.startActivity(fallback);
                    launched = true;
                } catch (Throwable t) {
                    Log.w(TAG, "Fallback launch failed for " + pkg, t);
                }
            }
        }

        if (!launched) {
            Toast.makeText(ctx, "无法启动: " + appInfo.getAppName(), Toast.LENGTH_SHORT).show();
        }
        if (callback != null) {
            try {
                callback.run();
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    private static ComponentName parseComponent(String action) {
        if (TextUtils.isEmpty(action) || !action.startsWith(SCHEME_COMPONENT)) {
            return null;
        }
        return ComponentName.unflattenFromString(action.substring(SCHEME_COMPONENT.length()));
    }
}
