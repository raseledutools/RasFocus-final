package com.rasel.RasFocus.selfcontrol.familybrowser.service

import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * YtPauseGuard — background/lock/floating এ audio চালু রাখা এবং একই সাথে
 * user এর আসল Pause কাজ করা — দুটো একসাথে।
 *
 * আগের সমস্যা:
 *  - HTMLMediaElement.prototype.pause কে no-op বানানো হয়েছিল → YouTube এর
 *    pause বাটন, notification/lock-screen pause কিছুই কাজ করত না।
 *  - প্রতি 2s এ keep-alive timer paused video আবার play() করত।
 *
 * এখন:
 *  - pause() শুধু তখনই block হয় যখন সেটা user/native intent ছাড়া আসে
 *    (visibility/lifecycle এর কারণে YouTube নিজে pause করতে চাইলে)।
 *  - user tap (1.5s window) বা native control (notification / lock screen /
 *    floating window) এর pause সবসময় কাজ করে এবং window.__rasUserPaused = true
 *    হয়; তখন কোনো keep-alive/auto-play কোড video resume করে না।
 *  - নতুন video playing হলে বা user আবার Play চাপলে flag clear হয়।
 */
object YtPauseGuard {

    private val GUARD_JS = """
        (function() {
            if (window.__rasPauseGuard__) return;
            window.__rasPauseGuard__ = true;
            window.__rasUserPaused = false;
            window.__rasLastGesture = 0;
            window.__rasNativeUntil = 0;

            var GESTURE_MS = 1500;
            var proto = HTMLMediaElement.prototype;
            var origPause = window.__rasOrigPause || proto.pause;
            var origPlay  = window.__rasOrigPlay  || proto.play;
            window.__rasOrigPause = origPause;
            window.__rasOrigPlay  = origPlay;

            function now() { return Date.now(); }
            function intent() {
                return (now() - window.__rasLastGesture) < GESTURE_MS ||
                       now() < window.__rasNativeUntil;
            }
            function report(playing) {
                try {
                    if (window.RasPlayBridge && window.RasPlayBridge.onState) {
                        window.RasPlayBridge.onState(playing);
                    }
                } catch (e) {}
            }
            function safePlay(v) {
                try {
                    var p = origPlay.call(v);
                    if (p && p.catch) p.catch(function() {});
                } catch (e) {}
            }

            // 1) user gesture tracking
            ['touchstart', 'touchend', 'pointerdown', 'pointerup',
             'mousedown', 'mouseup', 'click', 'keydown'].forEach(function(t) {
                try {
                    window.addEventListener(t, function() {
                        window.__rasLastGesture = now();
                    }, true);
                } catch (e) {}
            });

            // 2) pause(): শুধু intent ছাড়া system pause block
            proto.pause = function() {
                if (this.ended || window.__rasUserPaused || intent()) {
                    return origPause.apply(this, arguments);
                }
                return;
            };

            // 3) state tracking (media events bubble না — capture দরকার)
            document.addEventListener('pause', function(e) {
                var v = e.target;
                if (!v || v.tagName !== 'VIDEO' || v.ended) return;
                if (intent()) {
                    window.__rasUserPaused = true;
                    report(false);
                }
            }, true);
            document.addEventListener('play', function(e) {
                var v = e.target;
                if (!v || v.tagName !== 'VIDEO') return;
                if (intent()) window.__rasUserPaused = false;
            }, true);
            document.addEventListener('playing', function(e) {
                var v = e.target;
                if (!v || v.tagName !== 'VIDEO') return;
                window.__rasUserPaused = false;
                report(true);
            }, true);

            // 4) native control entry points
            window.__rasNativePause = function() {
                window.__rasUserPaused = true;
                window.__rasNativeUntil = now() + 1500;
                var vs = document.querySelectorAll('video');
                for (var i = 0; i < vs.length; i++) {
                    try { origPause.call(vs[i]); } catch (e) {}
                }
                report(false);
            };
            window.__rasNativePlay = function() {
                window.__rasUserPaused = false;
                window.__rasNativeUntil = now() + 1500;
                var vs = document.querySelectorAll('video');
                for (var i = 0; i < vs.length; i++) {
                    try { if (vs[i].paused && !vs[i].ended) safePlay(vs[i]); } catch (e) {}
                }
                report(true);
            };

            // 5) MediaSession: page এর play/pause handler আমাদেরটা দিয়ে বদলাও
            try {
                if ('mediaSession' in navigator) {
                    var ms = navigator.mediaSession;
                    var origSet = ms.setActionHandler.bind(ms);
                    ms.setActionHandler = function(action, handler) {
                        if (action === 'play' || action === 'pause') return;
                        return origSet(action, handler);
                    };
                    origSet('play',  function() { window.__rasNativePlay(); });
                    origSet('pause', function() { window.__rasNativePause(); });
                }
            } catch (e) {}
        })();
    """.trimIndent()

    private const val PLAY_CALL  = "try { window.__rasNativePlay && window.__rasNativePlay(); } catch(e) {}"
    private const val PAUSE_CALL = "try { window.__rasNativePause && window.__rasNativePause(); } catch(e) {}"

    /** Idempotent — যতবার ইচ্ছা call করা যায়। */
    fun install(view: WebView) {
        view.evaluateJavascript(GUARD_JS, null)
    }

    fun nativePause(view: WebView) {
        install(view)
        view.evaluateJavascript(PAUSE_CALL, null)
    }

    fun nativePlay(view: WebView) {
        install(view)
        view.evaluateJavascript(PLAY_CALL, null)
    }
}

/**
 * JS → Kotlin: page এ play/pause বদলালে BackgroundAudioService এর notification
 * state sync করতে broadcast পাঠায়। applicationContext ধরে রাখে (Activity leak নেই)।
 */
class YtPlayStateBridge(context: Context) {
    private val appCtx = context.applicationContext

    @JavascriptInterface
    fun onState(playing: Boolean) {
        try {
            appCtx.sendBroadcast(
                Intent(BackgroundAudioService.BROADCAST_STATE_SYNC).apply {
                    putExtra(BackgroundAudioService.EXTRA_PLAYING, playing)
                    setPackage(appCtx.packageName)
                }
            )
        } catch (_: Exception) { }
    }
}
