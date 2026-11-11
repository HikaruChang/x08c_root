package moe.rua.x08clauncher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(8, 12, 17);
    private static final int PANEL = Color.rgb(16, 23, 31);
    private static final int CARD_BG = Color.rgb(20, 29, 38);
    private static final int BORDER = Color.rgb(41, 55, 67);
    private static final int TEXT = Color.rgb(233, 240, 244);
    private static final int MUTED = Color.rgb(132, 151, 164);
    private static final int CYAN = Color.rgb(67, 220, 190);
    private static final int BLUE = Color.rgb(89, 167, 255);

    private final Typeface displayFace = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Typeface bodyFace = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface monoFace = Typeface.create("monospace", Typeface.NORMAL);

    private LinearLayout gridContainer;
    private TextView subtitleView;

    private static class AppEntry {
        String label;
        String pkg;
        String cls;
        Drawable icon;
        boolean isUser;
        int priority;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        populateApps();
    }

    private View buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(28), dp(20), dp(28), dp(28));
        page.setBackgroundColor(BG);

        page.addView(buildHeader(), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout console = new LinearLayout(this);
        console.setOrientation(LinearLayout.VERTICAL);
        console.setPadding(dp(18), dp(16), dp(18), dp(16));
        console.setBackground(card(PANEL, dp(18), BORDER, 1));
        LinearLayout.LayoutParams consoleLp = new LinearLayout.LayoutParams(-1, 0, 1);
        consoleLp.topMargin = dp(16);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        gridContainer = new LinearLayout(this);
        gridContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(gridContainer, new ScrollView.LayoutParams(-1, -2));

        console.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        page.addView(console, consoleLp);

        TextView footer = label(
                "TAP TO OPEN  ·  HOLD FOR APP INFO  ·  ZERO BACKGROUND SERVICE\n"
                + getString(R.string.copyright_notice),
                10, MUTED, monoFace);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(-1, -2);
        footerLp.topMargin = dp(12);
        page.addView(footer, footerLp);
        return page;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);

        View accent = new View(this);
        accent.setBackground(card(CYAN, dp(3), CYAN, 0));
        LinearLayout.LayoutParams accentLp = new LinearLayout.LayoutParams(dp(5), dp(50));
        accentLp.rightMargin = dp(15);
        header.addView(accent, accentLp);

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = label("X08E / APPS", 26, TEXT, displayFace);
        title.setLetterSpacing(0.05f);
        titleBlock.addView(title);

        subtitleView = label("LIGHTWEIGHT APP DRAWER  ·  v1.0.0", 11, MUTED, monoFace);
        subtitleView.setLetterSpacing(0.03f);
        titleBlock.addView(subtitleView);
        header.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1));

        addHeaderButton(header, "WIFI", BLUE, Color.rgb(16, 36, 56),
                v -> launchAction(Settings.ACTION_WIFI_SETTINGS));
        addHeaderButton(header, "SETTINGS", TEXT, CARD_BG,
                v -> launchAction(Settings.ACTION_SETTINGS));
        addHeaderButton(header, "ROOT", CYAN, Color.rgb(11, 48, 43),
                v -> launchPackage("moe.rua.x08croot"));
        addHeaderButton(header, "XIAOMI HOME", BG, CYAN,
                v -> launchPackage("com.xiaomi.micolauncher"));

        return header;
    }

    private void addHeaderButton(LinearLayout parent, String text, int fg, int bg, View.OnClickListener listener) {
        TextView btn = label(text, 11, fg, displayFace);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(14), dp(8), dp(14), dp(8));
        btn.setBackground(card(bg, dp(36), fg == BG ? CYAN : fg, 1));
        btn.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(8);
        parent.addView(btn, lp);
    }

    private void populateApps() {
        gridContainer.removeAllViews();
        PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN, null);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolves = pm.queryIntentActivities(query, 0);

        Set<String> hidden = new HashSet<>();
        hidden.add(getPackageName());
        hidden.add("com.xiaomi.micolauncher");
        hidden.add("com.sinyee.babybus.recommendapp");
        hidden.add("com.dancetv.bokecc.sqaredancetv");

        Set<String> seenPkg = new HashSet<>();
        List<AppEntry> entries = new ArrayList<>();
        for (ResolveInfo ri : resolves) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (hidden.contains(pkg) || seenPkg.contains(pkg)) continue;
            seenPkg.add(pkg);

            AppEntry e = new AppEntry();
            e.pkg = pkg;
            e.cls = ri.activityInfo.name;
            CharSequence cs = ri.loadLabel(pm);
            e.label = cs != null ? cs.toString().trim() : pkg;
            e.icon = ri.loadIcon(pm);
            int flags = ri.activityInfo.applicationInfo != null ? ri.activityInfo.applicationInfo.flags : 0;
            e.isUser = (flags & ApplicationInfo.FLAG_SYSTEM) == 0;
            if ("rua.moe.shindo".equals(pkg)) e.priority = 0;
            else if ("moe.rua.x08croot".equals(pkg)) e.priority = 1;
            else if (e.isUser) e.priority = 2;
            else e.priority = 3;
            entries.add(e);
        }

        Collections.sort(entries, (a, b) -> {
            if (a.priority != b.priority) return a.priority - b.priority;
            return a.label.compareToIgnoreCase(b.label);
        });

        subtitleView.setText("LIGHTWEIGHT APP DRAWER  ·  v1.0.0  ·  " + entries.size() + " APPS");

        final int cols = 3;
        LinearLayout row = null;
        for (int i = 0; i < entries.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) rowLp.topMargin = dp(10);
                gridContainer.addView(row, rowLp);
            }
            AppEntry app = entries.get(i);
            View cardView = buildAppCard(app);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(0, dp(76), 1);
            if (i % cols < cols - 1) cardLp.rightMargin = dp(10);
            row.addView(cardView, cardLp);
        }
        if (entries.size() % cols != 0 && row != null) {
            int rem = cols - (entries.size() % cols);
            for (int j = 0; j < rem; j++) {
                View spacer = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(76), 1);
                if (j < rem - 1) lp.rightMargin = dp(10);
                row.addView(spacer, lp);
            }
        }
    }

    private View buildAppCard(final AppEntry app) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackground(card(CARD_BG, dp(14), BORDER, 1));

        ImageView iconView = new ImageView(this);
        iconView.setImageDrawable(app.icon);
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        iconLp.rightMargin = dp(12);
        card.addView(iconView, iconLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView nameView = label(app.label, 14, TEXT, bodyFace);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(nameView);

        TextView pkgView = label(app.pkg, 10, app.isUser ? CYAN : MUTED, monoFace);
        pkgView.setSingleLine(true);
        pkgView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams pkgLp = new LinearLayout.LayoutParams(-1, -2);
        pkgLp.topMargin = dp(2);
        textCol.addView(pkgView, pkgLp);

        card.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1));

        card.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                intent.setComponent(new ComponentName(app.pkg, app.cls));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(intent);
            } catch (Exception e) {
                launchPackage(app.pkg);
            }
        });

        card.setOnLongClickListener(v -> {
            try {
                Intent info = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                info.setData(Uri.parse("package:" + app.pkg));
                info.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(info);
            } catch (Exception e) {
                Toast.makeText(this, app.pkg, Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        return card;
    }

    private void launchPackage(String pkg) {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } else {
                Toast.makeText(this, "Not found: " + pkg, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Launch failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void launchAction(String action) {
        try {
            Intent intent = new Intent(action);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open " + action, Toast.LENGTH_SHORT).show();
        }
    }

    private TextView label(String text, float size, int color, Typeface face) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(face);
        return view;
    }

    private GradientDrawable card(int fill, float radius, int stroke, int strokeWidthDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (strokeWidthDp > 0) drawable.setStroke(dp(strokeWidthDp), stroke);
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
