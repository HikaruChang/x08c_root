package moe.rua.x08croot;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayDeque;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(8, 12, 17);
    private static final int PANEL = Color.rgb(16, 23, 31);
    private static final int PANEL_ALT = Color.rgb(20, 29, 38);
    private static final int BORDER = Color.rgb(41, 55, 67);
    private static final int TEXT = Color.rgb(233, 240, 244);
    private static final int MUTED = Color.rgb(132, 151, 164);
    private static final int CYAN = Color.rgb(67, 220, 190);
    private static final int BLUE = Color.rgb(89, 167, 255);
    private static final int AMBER = Color.rgb(255, 183, 77);
    private static final int RED = Color.rgb(255, 104, 104);

    private final Typeface displayFace = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Typeface bodyFace = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface monoFace = Typeface.create("monospace", Typeface.NORMAL);
    private TextView statePill, streamPill, actionButton, rootValue, adbValue, sshValue, networkValue, trace;
    private ScrollView traceScroll;
    private boolean rootReady, adbRoot, sshReady;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildInterface());
        appendTrace("X08C temporary root console initialized");
        appendTrace("Safety policy: one exploit attempt per boot, manual or delayed auto");
        if (Rooter.attemptedThisBoot(this)) replayPreviousAutoLog();
        if (Rooter.succeededThisBoot(this)) showCompletedAttempt();
        else if (Rooter.installedThisBoot(this)) blockUntilReboot("APP UPDATED");
        else if (Rooter.attemptedThisBoot(this)) blockUntilReboot("ATTEMPT USED");
        else {
            armManualStart();
            if (Rooter.autoAllowed(this))
                appendTrace("Delayed auto-root is armed for 180 seconds after BOOT_COMPLETED");
            else
                appendTrace("Auto-root disabled: " + Rooter.autoStatus(this) + "; manual start remains available");
        }
    }

    private View buildInterface() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(28), dp(20), dp(28), dp(28));
        page.setBackgroundColor(BG);
        page.addView(buildHeader(), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.setPadding(0, dp(18), 0, dp(16));
        rootValue = addMetric(metrics, "ROOT ACCESS", "NOT STARTED", CYAN);
        adbValue = addMetric(metrics, "ADB · 5555", "UNKNOWN", BLUE);
        sshValue = addMetric(metrics, "SSH · 2222", "UNKNOWN", AMBER);
        networkValue = addMetric(metrics, "WLAN ADDRESS", "DISCOVERING", TEXT);
        page.addView(metrics, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout console = new LinearLayout(this);
        console.setOrientation(LinearLayout.VERTICAL);
        console.setPadding(dp(22), dp(16), dp(22), dp(18));
        console.setBackground(card(PANEL, dp(18), BORDER, 1));
        LinearLayout consoleHeader = new LinearLayout(this);
        consoleHeader.setGravity(Gravity.CENTER_VERTICAL);
        consoleHeader.addView(label("LIVE EXPLOIT TRACE", 15, TEXT, displayFace),
                new LinearLayout.LayoutParams(0, -2, 1));
        streamPill = pill("●  IDLE", MUTED, Color.rgb(27, 35, 43));
        consoleHeader.addView(streamPill);
        console.addView(consoleHeader, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = label("Kernel memory · credentials · service handoff", 12, MUTED, bodyFace);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(3);
        hintLp.bottomMargin = dp(12);
        console.addView(hint, hintLp);

        traceScroll = new ScrollView(this);
        traceScroll.setFillViewport(true);
        traceScroll.setBackground(card(Color.rgb(7, 11, 16), dp(12), Color.rgb(34, 47, 58), 1));
        trace = new TextView(this);
        trace.setTextColor(Color.rgb(195, 210, 219));
        trace.setTextSize(11);
        trace.setTypeface(monoFace);
        trace.setLineSpacing(0, 1.10f);
        trace.setPadding(dp(16), dp(13), dp(16), dp(13));
        trace.setTextIsSelectable(true);
        traceScroll.addView(trace, new ScrollView.LayoutParams(-1, -2));
        console.addView(traceScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        page.addView(console, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView footer = label("TEMPORARY ROOT  ·  REBOOT CLEARS ACCESS  ·  X08E / MT8167\n"
                + "Powered by NyphexAI. Developed by Hikaru(i@rua.moe)", 10, MUTED, monoFace);
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
        LinearLayout.LayoutParams accentLp = new LinearLayout.LayoutParams(dp(5), dp(52));
        accentLp.rightMargin = dp(15);
        header.addView(accent, accentLp);

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = label("X08C / ROOT", 28, TEXT, displayFace);
        title.setLetterSpacing(0.055f);
        titleBlock.addView(title);
        String version = "?";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) { }
        String versionTag = version.startsWith("v") ? version : ("v" + version);
        TextView subtitle = label("TEMPORARY ACCESS CONSOLE  ·  " + versionTag, 12, MUTED, monoFace);
        subtitle.setLetterSpacing(0.03f);
        titleBlock.addView(subtitle);
        header.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1));
        statePill = pill("READY", CYAN, Color.rgb(11, 48, 43));
        LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(-2, -2);
        stateLp.rightMargin = dp(10);
        header.addView(statePill, stateLp);
        actionButton = pill("START ROOT", BG, CYAN);
        header.addView(actionButton);
        return header;
    }

    private void armManualStart() {
        actionButton.setEnabled(true);
        actionButton.setAlpha(1.0f);
        actionButton.setOnClickListener(v -> startRootAttempt());
        appendTrace("Press START ROOT when the device is stable; do not retry after failure");
    }

    private void startRootAttempt() {
        AutoRootScheduler.cancel(this);
        actionButton.setEnabled(false);
        actionButton.setOnClickListener(null);
        actionButton.setAlpha(0.55f);
        actionButton.setText("RUNNING");
        setPill("INITIALIZING", AMBER, Color.rgb(53, 39, 17));
        setStreamPill("●  STREAMING", CYAN, Color.rgb(11, 48, 43));
        setMetric(rootValue, "CHECKING", AMBER);
        setMetric(adbValue, "WAITING", BLUE);
        setMetric(sshValue, "WAITING", AMBER);
        appendTrace("Exploit attempt armed; a reboot is required before any later retry");
        new Thread(() -> Rooter.run(this, line -> runOnUiThread(() -> handleLine(line)))).start();
    }

    private void showCompletedAttempt() {
        setPill("ROOT ACCESS READY", CYAN, Color.rgb(11, 48, 43));
        setStreamPill("●  COMPLETE", CYAN, Color.rgb(11, 48, 43));
        setMetric(rootValue, "AUTO ROOT · DONE", CYAN);
        actionButton.setText("ACTIVE THIS BOOT");
        actionButton.setEnabled(false);
        actionButton.setAlpha(0.55f);
        actionButton.setOnClickListener(null);
        appendTrace("The single root attempt for this boot completed successfully");
    }

    private void replayPreviousAutoLog() {
        File file = new File(getFilesDir(), "autoroot-last.log");
        if (!file.isFile()) return;
        ArrayDeque<String> tail = new ArrayDeque<>();
        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() >= 160) tail.removeFirst();
                tail.addLast(line);
            }
            reader.close();
            appendTrace("--- LAST AUTO-ROOT LOG ---");
            for (String saved : tail) handleLine(saved);
            appendTrace("--- END AUTO-ROOT LOG ---");
        } catch (Exception e) {
            appendTrace("Could not replay auto-root log: " + e);
        }
    }

    private void blockUntilReboot(String reason) {
        setPill("REBOOT REQUIRED", RED, Color.rgb(55, 24, 27));
        setStreamPill("●  LOCKED", AMBER, Color.rgb(53, 39, 17));
        setMetric(rootValue, reason, RED);
        actionButton.setText("REBOOT FIRST");
        actionButton.setEnabled(false);
        actionButton.setAlpha(0.45f);
        actionButton.setOnClickListener(null);
        appendTrace("BLOCKED: this boot already had an exploit attempt");
    }

    private TextView addMetric(LinearLayout row, String name, String value, int accent) {
        LinearLayout metric = new LinearLayout(this);
        metric.setOrientation(LinearLayout.VERTICAL);
        metric.setPadding(dp(16), dp(12), dp(16), dp(12));
        metric.setBackground(card(PANEL_ALT, dp(14), BORDER, 1));
        TextView nameView = label(name, 11, MUTED, monoFace);
        nameView.setLetterSpacing(0.04f);
        metric.addView(nameView);
        TextView valueView = label(value, 17, accent, displayFace);
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(-1, -2);
        valueLp.topMargin = dp(4);
        metric.addView(valueView, valueLp);
        LinearLayout.LayoutParams metricLp = new LinearLayout.LayoutParams(0, dp(72), 1);
        metricLp.rightMargin = row.getChildCount() < 3 ? dp(10) : 0;
        row.addView(metric, metricLp);
        return valueView;
    }

    private void handleLine(String line) {
        appendTrace(line);
        if (line.contains("[x08c] BLOCKED")) {
            blockUntilReboot(line.contains("installing") ? "APP UPDATED" : "ATTEMPT USED");
            return;
        }
        if (line.contains("[x08c] ERROR")) {
            setPill("REBOOT REQUIRED", RED, Color.rgb(55, 24, 27));
            setStreamPill("●  ERROR", RED, Color.rgb(55, 24, 27));
            actionButton.setText("REBOOT FIRST");
        }
        if (line.contains("Verified root credentials") || line.contains("[x08c] uid=0")) {
            rootReady = true;
            setMetric(rootValue, "UID 0 · READY", CYAN);
        }
        if (line.contains("network wlan0_ip=")) {
            String ip = line.substring(line.indexOf("network wlan0_ip=") + 17).trim();
            setMetric(networkValue, ip.length() == 0 ? "NO ADDRESS" : ip, ip.length() == 0 ? RED : TEXT);
        }
        if (line.contains("adb ROOT READY")) {
            adbRoot = true;
            setMetric(adbValue, "ROOT · READY", CYAN);
        } else if (line.contains("adb READY but NOT ROOT")) {
            setMetric(adbValue, "UID NOT ROOT", RED);
        } else if (line.contains("adb UNAVAILABLE")) {
            setMetric(adbValue, "OFFLINE", RED);
        }
        if (line.contains("ssh READY")) {
            sshReady = true;
            setMetric(sshValue, "ROOT · READY", CYAN);
        } else if (line.contains("ssh FAILED")) {
            setMetric(sshValue, "FAILED", RED);
        }
        if (line.contains("mtk-su exit=")) {
            if (rootReady && (adbRoot || sshReady)) {
                setPill("ROOT ACCESS READY", CYAN, Color.rgb(11, 48, 43));
                setStreamPill("●  COMPLETE", CYAN, Color.rgb(11, 48, 43));
                actionButton.setText("ACTIVE THIS BOOT");
            } else {
                setPill("REBOOT REQUIRED", RED, Color.rgb(55, 24, 27));
                setStreamPill("●  FAILED", RED, Color.rgb(55, 24, 27));
                actionButton.setText("REBOOT FIRST");
            }
        }
    }

    private void appendTrace(String line) {
        int color = Color.rgb(195, 210, 219);
        String lower = line.toLowerCase();
        if (line.contains("Verified") || line.contains(" READY") || line.contains("uid=0")) color = CYAN;
        else if (lower.contains("failed") || lower.contains("error") || lower.contains("unavailable") ||
                lower.contains("not root") || lower.contains("rejected") || line.contains("exit=1")) color = RED;
        else if (lower.contains("warning") || lower.contains("unstable")) color = AMBER;
        else if (lower.contains("scanning") || lower.contains("found") || lower.contains("decoded")) color = BLUE;
        // Keep the complete line in the persisted diagnostic log, but avoid
        // exposing source filenames and line numbers in the consumer UI.
        String displayLine = line.replaceFirst(
                "^[A-Za-z0-9_./-]+\\.(?:c|cc|cpp|cxx|h|hpp|java):\\d+\\s+", "");
        SpannableString rendered = new SpannableString("› " + displayLine + "\n");
        rendered.setSpan(new ForegroundColorSpan(color), 0, rendered.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        trace.append(rendered);
        traceScroll.post(() -> traceScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void setMetric(TextView view, String value, int color) {
        view.setText(value);
        view.setTextColor(color);
    }

    private void setPill(String text, int foreground, int background) {
        statePill.setText(text);
        statePill.setTextColor(foreground);
        statePill.setBackground(card(background, dp(40), foreground, 1));
    }

    private void setStreamPill(String text, int foreground, int background) {
        streamPill.setText(text);
        streamPill.setTextColor(foreground);
        streamPill.setBackground(card(background, dp(40), foreground, 1));
    }

    private TextView label(String text, float size, int color, Typeface face) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(face);
        return view;
    }

    private TextView pill(String text, int foreground, int background) {
        TextView view = label(text, 12, foreground, displayFace);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(15), dp(8), dp(15), dp(8));
        view.setBackground(card(background, dp(40), foreground, 1));
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
