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
                TopStocks t = TopStocks.fetch(this);
                t.save(this);
                refreshViews(this, t);
            } catch (Throwable e) {
                android.util.Log.e("JosangwonStock", "update failed", e);
                retry = params.getJobId() == NOW_ID;
            }
            try {
                jobFinished(params, retry);
            } catch (Throwable ignored) {
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    /** 위젯과 알림을 갱신한다. 알림을 띄웠으면 true. */
    static boolean refreshViews(Context c, TopStocks t) {
        try {
            TopWidgetProvider.render(c, t);
        } catch (Throwable e) {
            android.util.Log.e("JosangwonStock", "widget render", e);
        }
        return LockScreenNotifier.show(c, t);
    }

    /** 예약 작업을 기다리지 않고 지금 바로 받아서 갱신한다. 메인 스레드에서 불러도 된다. */
    static void updateNow(Context c, java.util.function.Consumer<String> onDone) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            String result;
            try {
                TopStocks t = TopStocks.fetch(app);
                t.save(app);
                result = refreshViews(app, t) ? null : "알림 권한이 꺼져 있습니다";
            } catch (Throwable e) {
                android.util.Log.e("JosangwonStock", "updateNow", e);
                TopStocks cached = TopStocks.load(app);
                if (cached != null) refreshViews(app, cached);
                result = "최신 데이터를 받지 못했습니다: " + e;
            }
            if (onDone != null) onDone.accept(result);
        }).start();
    }
}
