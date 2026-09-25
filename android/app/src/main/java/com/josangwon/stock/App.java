package com.josangwon.stock;

import android.app.Application;
import android.content.Context;

import java.io.PrintWriter;
import java.io.StringWriter;

/** 앱이 죽으면 원인을 저장해 두었다가 다음 실행 때 보여준다(원격 디버깅용). */
public class App extends Application {

    private static final String PREFS = "crash";
    private static final String KEY = "last";

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String text = "v" + BuildConfig.VERSION_NAME + " / Android " + android.os.Build.VERSION.RELEASE
                        + " / " + android.os.Build.MODEL + "\n" + sw;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY, text).commit();
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, e);
        });
    }

    /** 저장된 오류 기록을 꺼내고 지운다. 없으면 null. */
    static String takeCrash(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        String s = p.getString(KEY, null);
        if (s != null) p.edit().remove(KEY).apply();
        return s;
    }
}
