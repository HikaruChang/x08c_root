package moe.rua.x08croot;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
public class Rooter {
    private static final String PREFS = "x08c_safety";
    private static final String ATTEMPT_BOOT_ID = "attempt_boot_id";
    private static final String ATTEMPT_OUTCOME = "attempt_outcome";
    private static final String ATTEMPT_MODE = "attempt_mode";
    private static final String ATTEMPT_BUILD = "attempt_build";
    public interface Log { void line(String s); }
    // Copy an asset to filesDir and return its absolute path.
    static String asset(Context c, String name) throws IOException {
        File out = new File(c.getFilesDir(), name);
        InputStream in = c.getAssets().open(name);
        FileOutputStream os = new FileOutputStream(out);
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) os.write(b, 0, n);
        os.close(); in.close();
        return out.getAbsolutePath();
    }
    private static String bootId() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"));
            String id = reader.readLine();
            reader.close();
            if (id != null && id.length() >= 32) return id.trim();
        } catch (IOException ignored) { }
        // The target exposes boot_id. This coarse fallback is only for unusual
        // builds where proc_random is hidden from apps.
        long bootEpochBucket = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 30000L;
        return "fallback-" + bootEpochBucket;
    }

    public static boolean attemptedThisBoot(Context c) {
        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String previous = prefs.getString(ATTEMPT_BOOT_ID, "");
        return previous.equals(bootId());
    }

    public static boolean installedThisBoot(Context c) {
        try {
            long bootWallTime = System.currentTimeMillis() - SystemClock.elapsedRealtime();
            long updateTime = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
            return updateTime >= bootWallTime - 2000L;
        } catch (Exception ignored) {
            return true; // Fail closed when the install boundary cannot be proven.
        }
    }

    public static boolean succeededThisBoot(Context c) {
        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return attemptedThisBoot(c) && "success".equals(prefs.getString(ATTEMPT_OUTCOME, ""));
    }

    // A crash or ordinary failure during delayed auto-root disables automatic
    // attempts on later boots.  A successful manual run arms auto-root again.
    public static boolean autoAllowed(Context c) {
        if (installedThisBoot(c) || attemptedThisBoot(c)) return false;
        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String outcome = prefs.getString(ATTEMPT_OUTCOME, "");
        int attemptBuild = prefs.getInt(ATTEMPT_BUILD, 0);
        boolean priorBuildFailed = "running".equals(outcome) || "failed".equals(outcome);
        return !priorBuildFailed || attemptBuild != currentBuild(c);
    }

    public static String autoStatus(Context c) {
        String outcome = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(ATTEMPT_OUTCOME, "");
        if ("running".equals(outcome)) return "previous attempt did not finish";
        if ("failed".equals(outcome)) return "previous attempt failed";
        return "armed";
    }

    private static void markAttempt(Context c, boolean automatic) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(ATTEMPT_BOOT_ID, bootId())
                .putString(ATTEMPT_OUTCOME, "running")
                .putString(ATTEMPT_MODE, automatic ? "auto" : "manual")
                .putInt(ATTEMPT_BUILD, currentBuild(c))
                .commit();
    }

    private static int currentBuild(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static void markOutcome(Context c, boolean success) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(ATTEMPT_OUTCOME, success ? "success" : "failed")
                .commit();
    }

    // A failed CMDQ run must never be retried in the same boot. Reboot first so
    // the kernel, driver and allocator state all return to a known baseline.
    public static void run(Context c, Log log) {
        run(c, log, false);
    }

    public static synchronized boolean run(Context c, Log log, boolean automatic) {
        if (installedThisBoot(c)) {
            log.line("[x08c] BLOCKED: reboot once after installing or updating this app");
            return false;
        }
        if (attemptedThisBoot(c)) {
            log.line("[x08c] BLOCKED: an exploit attempt already ran this boot; reboot before retrying");
            return false;
        }
        if (automatic && !autoAllowed(c)) {
            log.line("[x08c] AUTO BLOCKED: " + autoStatus(c) + "; use one supervised manual attempt");
            return false;
        }
        markAttempt(c, automatic);
        try {
            String lib = c.getApplicationInfo().nativeLibraryDir;
            String script = asset(c, "postroot.sh");
            String files = c.getFilesDir().getAbsolutePath();
            String mtksu = lib + "/libmtksu2.so";
            new File(mtksu).setExecutable(true, false);
            String inner = "sh '" + script + "' '" + lib + "' '" + files + "'";
            log.line("[x08c] " + (automatic ? "delayed auto-root" : "manual root")
                    + " launching mtk_su (CVE-2020-0069, 0xf15h)...");
            ProcessBuilder pb = new ProcessBuilder(mtksu, inner);
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            final AtomicBoolean postrootVerified = new AtomicBoolean(false);
            Thread outputReader = new Thread(() -> {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String ln;
                    while ((ln = r.readLine()) != null) {
                        if (ln.contains("[x08c] POSTROOT VERIFIED: ssh=root adb=root")) {
                            postrootVerified.set(true);
                            markOutcome(c, true);
                        }
                        log.line(ln);
                    }
                } catch (IOException e) {
                    if (!postrootVerified.get()) log.line("[x08c] native log reader stopped: " + e);
                }
            }, "x08c-native-log");
            outputReader.start();
            int rc = p.waitFor();
            outputReader.join(1000L);
            if (outputReader.isAlive()) {
                try { p.getInputStream().close(); } catch (IOException ignored) { }
                outputReader.join(1000L);
            }
            log.line("[x08c] mtk-su exit=" + rc);
            boolean verified = rc == 0 && postrootVerified.get();
            if (!verified) {
                log.line("[x08c] COMPLETION REJECTED: exit=" + rc
                        + " postroot_verified=" + postrootVerified.get());
            }
            markOutcome(c, verified);
            return verified;
        } catch (Exception e) {
            markOutcome(c, false);
            log.line("[x08c] ERROR " + e);
            return false;
        }
    }
}
