package moe.rua.x08croot;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

public final class AutoRootScheduler {
    public static final int JOB_ID = 0x583043;
    public static final long COOLDOWN_MS = 180000L;

    private AutoRootScheduler() { }

    public static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, AutoRootJobService.class))
                .setMinimumLatency(COOLDOWN_MS)
                .setOverrideDeadline(COOLDOWN_MS + 120000L)
                .build();
        scheduler.schedule(job);
    }

    public static void cancel(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) scheduler.cancel(JOB_ID);
    }
}
