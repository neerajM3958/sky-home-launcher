package io.freemytv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewOutlineProvider;
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

    /** The page's address: YouTube's embedded player wants to know where it is shown. */
    private static final String ORIGIN = "https://freemytv.io";
    private static final long START_MS = 12_000;

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
    private boolean playing, paused, open;
    private float radius;
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

    View view() {
        return web;
    }

    /** True from play() until stop(): the player is loading, playing or paused. */
    boolean active() {
        return open;
    }

    /** Starts the trailer. The view is made on first use and must be laid out over the card by the caller (`radius`: its corners). */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    View play(String youtubeId, float cornerRadius) {
        radius = cornerRadius;
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
            web.setWebViewClient(new WebViewClient());
            web.setWebChromeClient(new WebChromeClient());
            web.addJavascriptInterface(new Bridge(), "FMT");
            web.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline outline) {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            web.setClipToOutline(true);
        }
        open = true;
        playing = paused = false;
        web.setAlpha(0);
        String html = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}</style></head><body><div id='p'></div><script>"
                + "var player;var t=document.createElement('script');t.src='https://www.youtube.com/iframe_api';document.head.appendChild(t);"
                + "function onYouTubeIframeAPIReady(){player=new YT.Player('p',{width:'100%',height:'100%',videoId:'" + youtubeId + "',"
                + "playerVars:{autoplay:1,controls:0,disablekb:1,fs:0,modestbranding:1,playsinline:1,rel:0,iv_load_policy:3,origin:'" + ORIGIN + "'},"
                + "events:{onReady:function(e){e.target.playVideo();},onStateChange:function(e){FMT.state(e.data);},onError:function(e){FMT.error(e.data);}}});}"
                + "</script></body></html>";
        web.loadDataWithBaseURL(ORIGIN + "/", html, "text/html", "utf-8", null);
        main.removeCallbacks(giveUp);
        main.postDelayed(giveUp, START_MS);
        return web;
    }

    /** OK or the remote's Play/Pause: pause, or play again. */
    void togglePause() {
        if (web == null || !open || !playing) return;
        paused = !paused;
        web.evaluateJavascript(paused ? "player.pauseVideo()" : "player.playVideo()", null);
    }

    void stop() {
        open = false;
        main.removeCallbacks(giveUp);
        stopPage();
    }

    private void stopPage() {
        playing = paused = false;
        if (web != null) {
            web.setAlpha(0);
            web.loadUrl("about:blank");
        }
    }

    /** Gives the WebView's memory back (the home screen leaves the screen). */
    void release() {
        stop();
        if (web != null) {
            if (web.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) web.getParent()).removeView(web);
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
                        web.setAlpha(1);
                        listener.onPlaying();
                    }
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
