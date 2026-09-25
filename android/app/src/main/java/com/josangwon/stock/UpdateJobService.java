package com.josangwon.stock;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** 30분마다(그리고 요청 시 즉시) 상위 3종목을 받아 위젯과 잠금화면 알림을 갱신한다. */
public class UpdateJobService extends JobService {

    private static final int PERIODIC_ID = 1;
    private static final int NOW_ID = 2;
    private static final long PERIOD_MS = 30 * 60 * 1000L;

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(PERIODIC_ID) == null) {
            js.schedule(new JobInfo.Builder(PERIODIC_ID, new ComponentName(c, UpdateJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build());
        }
    }

    static void runNow(Context c) {
        c.getSystemService(JobScheduler.class).schedule(
                new JobInfo.Builder(NOW_ID, new ComponentName(c, UpdateJobService.class))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setOverrideDeadline(10_000)
                        .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            boolean retry = false;
            try {
                TopStocks t = TopStocks.fetch();
                t.save(this);
                refreshViews(this, t);
            } catch (Exception e) {
                retry = params.getJobId() == NOW_ID;
            }
            jobFinished(params, retry);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    static void refreshViews(Context c, TopStocks t) {
        TopWidgetProvider.render(c, t);
        LockScreenNotifier.show(c, t);
    }
}
