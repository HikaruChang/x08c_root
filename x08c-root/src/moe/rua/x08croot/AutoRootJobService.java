package moe.rua.x08croot;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class AutoRootJobService extends JobService {
    private void write(File file, String line, boolean append) {
        if (file == null) return;
        try {
            FileWriter writer = new FileWriter(file, append);
            if (line != null) writer.write(line + "\n");
            writer.close();
        } catch (IOException ignored) { }
    }

    private File externalLog() {
        File dir = getExternalFilesDir(null);
        return dir == null ? null : new File(dir, "autoroot-last.log");
    }

    private void resetLogs() {
        write(new File(getFilesDir(), "autoroot-last.log"), null, false);
        write(externalLog(), null, false);
    }

    private void log(String line) {
        android.util.Log.i("X08CRoot", line);
        write(new File(getFilesDir(), "autoroot-last.log"), line, true);
        write(externalLog(), line, true);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            resetLogs();
            log("[x08c] auto-root job started after 180 second boot cooldown");
            if (!Rooter.autoAllowed(this)) {
                log("[x08c] AUTO BLOCKED: " + Rooter.autoStatus(this));
            } else {
                Rooter.run(this, this::log, true);
            }
            jobFinished(params, false);
        }, "x08c-auto-root").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        log("[x08c] auto-root job stopped; it will not be rescheduled this boot");
        return false;
    }
}
