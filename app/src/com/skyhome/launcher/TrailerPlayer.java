package com.skyhome.launcher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * A trailer inside the home screen: YouTube's own embedded player in a WebView (the only way YouTube allows an app to
 * play its videos in place). The view stays invisible until the video really plays, so the backdrop is never replaced by
 * a black box; an error, or no sign of playing in time, is reported to the caller, which opens the YouTube app instead.
 */
final class TrailerPlayer {
    interface Listener {
        void onPlaying();
        void onEnded();
        void onFailed();
    }

    /** The page's address: YouTube's embedded player wants to know where it is shown. Not a real site: ".invalid"
     *  (RFC 2606) is reserved for exactly this, an origin that's never meant to resolve. */
    private static final String ORIGIN = "https://skyhomelauncher.invalid";
    private static final long START_MS = 12_000;

    /**
     * Runs inside YouTube's embed page, which shows its own interface (title, play/pause, buffering spinner, branding): it
     * only reports what the <video> does (1 playing, 2 paused, 0 ended, 3 waiting) and an error screen, and says AV1 isn't
     * supported so the player picks VP9 or H.264 (some TVs' AV1 decoders show black while the sound plays).
     */
    private static final String PAGE_SCRIPT = "(function(){if(window.__fmt)return;window.__fmt=1;"
            + "try{var m=window.MediaSource;if(m&&m.isTypeSupported){var o=m.isTypeSupported.bind(m);"
            + "m.isTypeSupported=function(t){return /av01/i.test(t)?false:o(t);};}"
            + "var mc=navigator.mediaCapabilities;if(mc&&mc.decodingInfo){var d=mc.decodingInfo.bind(mc);"
            + "mc.decodingInfo=function(c){var v=(c&&c.video&&c.video.contentType)||'';"
            + "if(/av01/i.test(v))return Promise.resolve({supported:false,smooth:false,powerEfficient:false});return d(c);};}}catch(e){}"
            + "function hook(v){if(v.__h)return;v.__h=1;"
            + "v.addEventListener('playing',function(){FMT.state(1)});v.addEventListener('pause',function(){FMT.state(2)});"
            + "v.addEventListener('waiting',function(){FMT.state(3)});v.addEventListener('ended',function(){FMT.state(0)});}"
            + "setInterval(function(){var v=document.querySelector('video');if(v){hook(v);"
            + "if(!window.__on&&!v.paused&&v.currentTime>0.2){window.__on=1;FMT.state(1);}}"
            + "if(document.querySelector('.ytp-error')&&!window.__err){window.__err=1;FMT.error(150);}},500);})();";

    /** A WebView is on the TV (some boxes ship without one). */
    static boolean available() {
        try {
            return WebView.getCurrentWebViewPackage() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private android.app.Dialog dialog;
    private boolean playing, paused, open;
    private final Runnable giveUp;

    TrailerPlayer(Context c, Listener l) {
        context = c;
        listener = l;
        giveUp = () -> {
            if (open && !playing) {
                AppLog.w("trailer: no sign of playing");
                listener.onFailed();
            }
        };
    }

    /** The trailer was asked for and isn't showing yet (the home screen draws a spinner meanwhile). */
    boolean loading() {
        return open && !playing;
    }

    /** True from play() until stop(): the player is loading, playing or paused. */
    boolean active() {
        return open;
    }

    /**
     * Starts the trailer in a window of its own over the card's video area (x, y, width, height in pixels): the TV decodes
     * video onto a plane under the app's window, which the home screen's opaque layers would cover; a window of its own
     * with nothing but the WebView in it leaves that plane showing. The window can't take focus: keys still reach the home screen.
     */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    void play(String youtubeId, int x, int y, int width, int height) {
        stopPage();
        if (web == null) {
            web = new WebView(context);
            web.setFocusable(false);
            web.setFocusableInTouchMode(false);
            web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            web.setBackgroundColor(0xFF000000);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            web.setWebViewClient(new WebViewClient() {
                @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                    if (url != null && url.startsWith("https://www.youtube.com/embed/")) view.evaluateJavascript(PAGE_SCRIPT, null);
                }

                @Override public void onPageFinished(WebView view, String url) {
                    if (url != null && url.startsWith("https://www.youtube.com/embed/")) view.evaluateJavascript(PAGE_SCRIPT, null);
                }
            });
            web.setWebChromeClient(new WebChromeClient());
            web.addJavascriptInterface(new Bridge(), "FMT");
        }
        if (dialog == null) {
            dialog = new android.app.Dialog(context, android.R.style.Theme_Translucent_NoTitleBar);
            dialog.setContentView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
            Window w = dialog.getWindow();
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            w.setFlags(flags, flags);
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setDimAmount(0);
            w.setGravity(Gravity.TOP | Gravity.START);
        }
        WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
        lp.x = x;
        lp.y = y;
        lp.width = width;
        lp.height = height;
        lp.alpha = 0;       // invisible until the video really plays
        dialog.getWindow().setAttributes(lp);
        if (!dialog.isShowing()) dialog.show();
        open = true;
        playing = paused = false;
        // YouTube's embed page itself (not inside a page of ours): the page's <video> can then be watched and controlled.
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Referer", ORIGIN + "/");
        web.loadUrl("https://www.youtube.com/embed/" + youtubeId
                + "?autoplay=1&controls=1&disablekb=1&fs=0&modestbranding=1&playsinline=1&rel=0&iv_load_policy=3"
                + "&origin=" + ORIGIN + "&widget_referrer=" + ORIGIN, headers);
        main.removeCallbacks(giveUp);
        main.postDelayed(giveUp, START_MS);
    }

    boolean paused() {
        return paused;
    }

    /** OK or the remote's Play/Pause: pause, or play again. */
    void togglePause() {
        if (web == null || !open || !playing) return;
        paused = !paused;
        web.evaluateJavascript("var v=document.querySelector('video');if(v){v." + (paused ? "pause" : "play") + "();}", null);
    }

    void stop() {
        open = false;
        main.removeCallbacks(giveUp);
        stopPage();
    }

    private void stopPage() {
        playing = paused = false;
        if (web != null) web.loadUrl("about:blank");
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    /** Gives the WebView's memory back (the home screen leaves the screen). */
    void release() {
        stop();
        dialog = null;
        if (web != null) {
            web.destroy();
            web = null;
        }
    }

    /** What the page says, on a WebView thread. */
    private final class Bridge {
        @JavascriptInterface public void state(int s) {
            main.post(() -> {
                if (!open) return;
                if (s == 1) {                  // playing
                    paused = false;
                    if (!playing) {
                        playing = true;
                        main.removeCallbacks(giveUp);
                        if (dialog != null) {
                            WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
                            lp.alpha = 1;
                            dialog.getWindow().setAttributes(lp);
                        }
                        listener.onPlaying();
                    }
                } else if (s == 2 && playing) {     // paused (by the remote, or the page's own button)
                    paused = true;
                } else if (s == 0) {           // ended
                    listener.onEnded();
                }
            });
        }

        @JavascriptInterface public void error(int code) {
            main.post(() -> {
                if (!open) return;
                AppLog.w("trailer: the player says " + code);
                listener.onFailed();
            });
        }
    }
}
