package com.josangwon.stock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 재부팅 후 마지막 값으로 알림을 다시 띄우고 갱신을 이어간다. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            try {
                LockScreenNotifier.show(c, TopStocks.load(c));
                UpdateJobService.schedule(c);
                UpdateJobService.runNow(c);
            } catch (Throwable e) {
                android.util.Log.e("JosangwonStock", "boot", e);
            }
        }
    }
}
