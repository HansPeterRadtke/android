package com.hans.android.calendarreminder;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

public final class SyncJobService extends JobService {
    private static final int PERIODIC_ID=13011;
    private static final int IMMEDIATE_ID=13012;
    private volatile boolean stopped=false;

    public static void ensurePeriodic(Context c) {
        if(!new CalendarStore(c).liveEnabled()) return;
        JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(js.getPendingJob(PERIODIC_ID)!=null) return;
        JobInfo job=new JobInfo.Builder(PERIODIC_ID,new ComponentName(c,SyncJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(15*60_000L).setPersisted(true).build();
        js.schedule(job);
    }
    public static void stopPeriodic(Context c) {
        JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.cancel(PERIODIC_ID);
    }
    public static void requestSync(Context c) {
        ensurePeriodic(c);
        JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        JobInfo job=new JobInfo.Builder(IMMEDIATE_ID,new ComponentName(c,SyncJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(0).setOverrideDeadline(20_000).build();
        js.schedule(job);
    }

    @Override public boolean onStartJob(JobParameters params) {
        stopped=false;
        new Thread(() -> {
            SyncEngine.run(getApplicationContext());
            if(!stopped) jobFinished(params,false);
        },"raspi-calendar-sync").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        stopped=true;
        // Stopping a network job does not cancel a running socket immediately;
        // the bounded network timeouts and next periodic sync provide recovery.
        return true;
    }
}
