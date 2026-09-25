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
        webView = new WebView(this);
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
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    view.loadDataWithBaseURL(BuildConfig.APP_URL, OFFLINE_PAGE, "text/html", "utf-8", null);
                }
            }
        });

        // 잠금화면 알림 권한(Android 13+)을 요청하고, 위젯·알림 갱신을 시작한다.
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        LockScreenNotifier.show(this, TopStocks.load(this));
        UpdateJobService.schedule(this);
        UpdateJobService.runNow(this);

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(BuildConfig.APP_URL);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 앱으로 돌아올 때마다 최신 분석 결과를 다시 불러온다(처음 켤 때는 이미 불러오는 중).
        if (firstResume) {
            firstResume = false;
        } else {
            webView.reload();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        UpdateJobService.runNow(this);  // 허용 직후 바로 알림을 띄운다
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
