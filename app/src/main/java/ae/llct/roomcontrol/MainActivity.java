package ae.llct.roomcontrol;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Villa 21 Room Control — wraps the bundled web UI (assets/index.html) in a full-screen WebView.
 * The page talks to the MQTT broker over WebSockets (ws://192.168.10.5:1880/mqtt by default).
 */
public class MainActivity extends Activity {

    private WebView web;
    private NativeMqtt mqtt;
    private PermissionRequest pendingPerm;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#070A12"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // saves layout + settings (localStorage)
        s.setAllowFileAccess(true);
        s.setTextZoom(100);                    // ignore system font scaling so the layout stays intact
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        web.setWebViewClient(new WebViewClient());
        // enables confirm()/alert() dialogs + microphone for Music mode
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> {
                    boolean audio = false;
                    for (String r : req.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) audio = true;
                    if (!audio) { req.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        req.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        pendingPerm = req;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
                    }
                });
            }
        });
        // Native MQTT over TCP (port 1883) — events are forwarded to window.__mq(event, a, b) in the page
        mqtt = new NativeMqtt((ev, a, b) -> runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript("window.__mq&&__mq(" + JSONObject.quote(ev) + ","
                    + JSONObject.quote(a) + "," + JSONObject.quote(b) + ")", null);
        }));
        web.addJavascriptInterface(new Bridge(), "Android");

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("file:///android_asset/index.html");
    }

    /** Functions callable from the page as window.Android.xxx() */
    private class Bridge {
        @JavascriptInterface
        public void vibrate(int ms) {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(Math.max(1, ms), VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        }

        @JavascriptInterface
        public void keepScreenOn(final boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface public void mqttConnect(String host, int port, String user, String pass, String clientId) {
            mqtt.connect(host, port, user, pass, clientId);
        }
        @JavascriptInterface public void mqttPublish(String topic, String payload) { mqtt.publish(topic, payload); }
        @JavascriptInterface public void mqttSubscribe(String topic) { mqtt.subscribe(topic); }
        @JavascriptInterface public void mqttDisconnect() { mqtt.disconnect(); }
        @JavascriptInterface public boolean mqttConnected() { return mqtt.isConnected(); }

        /** Direct Tasmota web API call (no CORS limits). Result -> window.__http(id, httpCode, body) */
        @JavascriptInterface public void httpGet(final String id, final String url, final int timeoutMs) {
            new Thread(() -> {
                int code = 0; String body = "";
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(timeoutMs); c.setReadTimeout(timeoutMs); c.setUseCaches(false);
                    code = c.getResponseCode();
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    if (in != null) {
                        ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
                        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                        body = b.toString("UTF-8"); in.close();
                    }
                    c.disconnect();
                } catch (Exception e) { code = 0; body = String.valueOf(e.getMessage()); }
                final int fc = code; final String fb = body;
                runOnUiThread(() -> { if (web != null) web.evaluateJavascript(
                        "window.__http&&__http(" + JSONObject.quote(id) + "," + fc + "," + JSONObject.quote(fb) + ")", null); });
            }, "http").start();
        }

        @JavascriptInterface
        public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return ""; }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 7 && pendingPerm != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
                pendingPerm.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else pendingPerm.deny();
            pendingPerm = null;
        }
    }

    /** Back closes an open sheet first; otherwise the app goes to background. */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.handleBack ? handleBack() : false", value -> {
            if (!"true".equals(value)) moveTaskToBack(true);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            web.evaluateJavascript("window.onAppResume && onAppResume()", null);
        }
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (mqtt != null) mqtt.disconnect();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
