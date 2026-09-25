package com.josangwon.stock;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** 조상원 주식 웹앱(GitHub Pages)을 전체 화면으로 보여준다. 분석 결과는 웹에서 매번 새로 받는다. */
public class MainActivity extends Activity {

    private static final String OFFLINE_PAGE =
            "<html><body style='background:#000;color:#fff;font-family:sans-serif;text-align:center;padding-top:40vh'>"
            + "<p>인터넷에 연결할 수 없습니다.</p>"
            + "<p><a style='color:#7d9df0' href='" + BuildConfig.APP_URL + "'>다시 시도</a></p></body></html>";

    private WebView webView;
    private boolean firstResume = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String crash = App.takeCrash(this);
        try {
            webView = new WebView(this);
        } catch (Throwable e) {
            // 안드로이드 시스템 WebView가 없거나 업데이트 중이면 여기서 실패한다.
            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setPadding(40, 200, 40, 40);
            tv.setText("화면을 여는 데 필요한 'Android System WebView'를 불러오지 못했습니다.\n"
                    + "Play 스토어에서 Android System WebView와 Chrome을 업데이트한 뒤 다시 열어 주세요.\n\n" + e);
            setContentView(tv);
            return;
        }
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri.toString().startsWith(BuildConfig.APP_URL)) {
                    return false;
                }
                // 네이버 종목 페이지 등 외부 링크는 브라우저로 연다.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Throwable ignored) {
                    // 열 수 있는 앱이 없으면 무시
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    view.loadDataWithBaseURL(BuildConfig.APP_URL, OFFLINE_PAGE, "text/html", "utf-8", null);
                }
            }
        });

        if (crash != null) {
            showCrash(crash);
        } else {
            // 화면이 뜬 뒤에 알림 권한 요청과 위젯·알림 갱신을 시작한다. 실패해도 화면은 유지.
            webView.post(this::startBackgroundUpdates);
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(BuildConfig.APP_URL);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView == null) return;
        // 앱으로 돌아올 때마다 최신 분석 결과를 다시 불러온다(처음 켤 때는 이미 불러오는 중).
        if (firstResume) {
            firstResume = false;
        } else {
            webView.reload();
            UpdateJobService.updateNow(this, null);  // 설정에서 알림을 켜고 돌아왔을 때도 바로 뜨게
        }
    }

    private void startBackgroundUpdates() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
            UpdateJobService.schedule(this);
            refreshNotification(false);
        } catch (Throwable e) {
            android.util.Log.e("JosangwonStock", "background updates", e);
        }
    }

    /** 지금 바로 TOP3를 받아 알림·위젯을 띄운다. 알림이 꺼져 있으면 설정으로 안내한다. */
    private void refreshNotification(boolean askIfDisabled) {
        UpdateJobService.updateNow(this, error -> runOnUiThread(() -> {
            if (isFinishing()) return;
            if (!LockScreenNotifier.enabled(this)) {
                if (askIfDisabled || android.os.Build.VERSION.SDK_INT < 33) askNotificationSettings();
            } else if (error != null) {
                android.widget.Toast.makeText(this, error, android.widget.Toast.LENGTH_LONG).show();
            }
        }));
    }

    private void askNotificationSettings() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("알림이 꺼져 있어요")
                .setMessage("상위 3종목을 알림창에 보려면 '조상원 주식' 알림을 켜 주세요. 소리나 팝업은 없습니다.")
                .setPositiveButton("설정 열기", (d, w) -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
                    } catch (Throwable ignored) {
                    }
                })
                .setNegativeButton("나중에", null)
                .show();
    }

    /** 지난번에 앱이 죽은 원인을 보여준다. 캡처해서 보내면 고칠 수 있다. */
    private void showCrash(String text) {
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setTextSize(11);
        tv.setPadding(40, 20, 40, 20);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        new android.app.AlertDialog.Builder(this)
                .setTitle("지난번 오류 기록 (캡처해서 보내주세요)")
                .setView(sv)
                .setPositiveButton("닫기", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshNotification(true);  // 허용 직후 바로 알림을 띄운다(거부했으면 설정 안내)
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
