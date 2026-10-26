package com.loopguard.tiktok

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Watches TikTok through the accessibility API.
 *
 *  - Session time: a 1 s ticker adds time only while TikTok is the foreground app.
 *  - Loops: TikTok's progress bar is read from the accessibility tree (RangeInfo). When the bar jumps from
 *    the end (>80 %) back to the start (<20 %) on the same video, that is one completed loop.
 *  - A vertical swipe (TYPE_VIEW_SCROLLED) or a changed caption means a new video: the loop counter resets.
 *
 * Loop detection is best-effort: it depends on TikTok exposing its progress bar to accessibility.
 */
class LoopGuardService : AccessibilityService() {

    companion object {
        val TIKTOK_PACKAGES = setOf(
            "com.zhiliaoapp.musically",     // TikTok global
            "com.zhiliaoapp.musically.go",  // TikTok Lite
            "com.ss.android.ugc.trill"      // TikTok (Asia)
        )
        private const val CHANNEL_ID = "loopguard"
        private const val RETURN_GRACE_MS = 60_000L   // leaving < 60 s keeps the session running
        private const val SCAN_INTERVAL_MS = 250L
        private const val LONG_VIDEO_SEC = 180
        // Avoid counting the same wrap more than once if TikTok emits duplicate UI events.
        private const val LOOP_DEBOUNCE_MS = 3_000L
    }

    private class Scan(
        var fraction: Float = -1f,
        var fractionScore: Int = -1,   // -1 = nothing found yet, so a generic range (score 0) still works as fallback
        var max: Int = 0,
        var desc: String? = null,
        var creator: String? = null,
        var visited: Int = 0
    )

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private var inTikTok = false
    private var tiktokPkg = ""
    private var leftAt = 0L
    private var activeMs = 0L
    private var loops = 0
    private var lastFraction = -1f
    private var lastLoopAt = 0L
    private var durationSec = -1
    private var title = ""
    private var lastScan = 0L
    private var intervening = false

    private val ticker = object : Runnable {
        override fun run() {
            if (inTikTok && !intervening && prefs.enabled && prefs.isActiveNow()) {
                activeMs += 1000
                if (prefs.timeLimitEnabled && activeMs >= prefs.timeLimitMin * 60_000L) {
                    intervene("Time limit (${prefs.timeLimitMin} min)")
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = leaveTikTok()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        handler.post(ticker)
        ContextCompat.registerReceiver(
            this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        if (pkg in TIKTOK_PACKAGES) {
            if (!inTikTok) enterTikTok(pkg)
            if (!prefs.enabled || !prefs.isActiveNow() || intervening) return
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> onNewVideo()
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> scan()
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            pkg != "com.android.systemui" && !pkg.contains("inputmethod", ignoreCase = true)
        ) {
            leaveTikTok()
        }
    }

    // ---------------- Session tracking ----------------

    private fun enterTikTok(pkg: String) {
        val now = SystemClock.uptimeMillis()
        if (leftAt != 0L && now - leftAt > RETURN_GRACE_MS) resetSession()
        inTikTok = true
        tiktokPkg = pkg
    }

    private fun leaveTikTok() {
        if (inTikTok) {
            inTikTok = false
            leftAt = SystemClock.uptimeMillis()
        }
    }

    private fun resetSession() {
        activeMs = 0
        title = ""
        onNewVideo()
    }

    private fun onNewVideo() {
        loops = 0
        lastFraction = -1f
        lastLoopAt = 0L
        durationSec = -1
    }

    // ---------------- Loop detection ----------------

    private fun scan() {
        val now = SystemClock.uptimeMillis()
        if (now - lastScan < SCAN_INTERVAL_MS) return
        lastScan = now

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() !in TIKTOK_PACKAGES) return

        val s = Scan()
        walk(root, 0, s)

        val newTitle = listOfNotNull(s.creator, s.desc).joinToString(" · ").take(80)
        if (newTitle.isNotEmpty() && newTitle != title) {
            if (title.isNotEmpty()) onNewVideo()   // caption changed => different video
            title = newTitle
        }

        val f = s.fraction
        if (f < 0f) return

        if (s.max > 0) durationSec = if (s.max > 1000) s.max / 1000 else s.max // ms or seconds

        val nowMs = SystemClock.uptimeMillis()
        // Count only a clear end-to-start wrap, and debounce duplicate accessibility events.
        if (lastFraction > 0.80f && f < 0.20f &&
            (lastLoopAt == 0L || nowMs - lastLoopAt >= LOOP_DEBOUNCE_MS)
        ) {
            lastLoopAt = nowMs
            loops++
            if (prefs.loopLimitEnabled && loops >= effectiveLoopLimit()) {
                intervene("Loop limit ($loops×)")
            }
        }
        lastFraction = f
    }

    private fun effectiveLoopLimit(): Int {
        val limit = prefs.loopLimit
        return if (prefs.halveLong && durationSec > LONG_VIDEO_SEC) Math.max(1, limit / 2) else limit
    }

    private fun walk(n: AccessibilityNodeInfo?, depth: Int, s: Scan) {
        if (n == null || depth > 30 || s.visited > 600) return
        s.visited++

        val id = n.viewIdResourceName ?: ""
        val className = n.className?.toString().orEmpty()
        val lowerId = id.lowercase()
        // Prefer an actual playback/progress/seek bar over unrelated sliders in the view tree.
        // If TikTok exposes no identifiable progress bar, retain a generic range as a fallback.
        val rangeScore = when {
            listOf("progress", "seek", "playback", "video_time").any { lowerId.contains(it) } -> 4
            className.contains("SeekBar", ignoreCase = true) -> 3
            className.contains("ProgressBar", ignoreCase = true) -> 2
            else -> 0
        }
        val r = n.rangeInfo
        if (r != null && r.max > r.min && rangeScore > s.fractionScore) {
            s.fraction = ((r.current - r.min) / (r.max - r.min)).coerceIn(0f, 1f)
            s.max = r.max.toInt()
            s.fractionScore = rangeScore
        }

        val text = n.text?.toString()
        if (!text.isNullOrBlank()) {
            if (text.startsWith("@") && s.creator == null) s.creator = text
            else if (id.contains("desc", ignoreCase = true) && s.desc == null) s.desc = text
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, s)
    }

    // ---------------- Intervention ----------------

    private fun intervene(reason: String) {
        if (intervening) return
        intervening = true

        val loopsNow = loops
        val titleNow = title.ifEmpty { "Unknown video" }

        if (prefs.haptic) vibrate()
        val warn = prefs.chime
        if (warn) {
            playChime()
            Toast.makeText(this, "TikTok closing in 3 seconds…", Toast.LENGTH_LONG).show()
        }
        handler.postDelayed({ execute(reason, titleNow, loopsNow) }, if (warn) 3000L else 0L)
    }

    private fun execute(reason: String, titleNow: String, loopsNow: Int) {
        val pkg = tiktokPkg
        when (prefs.action) {
            EndAction.BACKGROUND -> {
                // Home pauses playback and keeps TikTok (and the current video) alive in recents.
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            EndAction.CLOSE -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                handler.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    // Only works once TikTok is in the background; harmless otherwise.
                    (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg)
                    if (prefs.lockout) {
                        handler.postDelayed({
                            startActivity(
                                Intent(this, LockoutActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            )
                        }, 300)
                    }
                }, 400)
            }
        }

        prefs.recordIntervention(titleNow, loopsNow, prefs.action.name, reason)
        notifyUser(reason)

        // Fresh allowance the next time TikTok is opened.
        inTikTok = false
        leftAt = SystemClock.uptimeMillis()
        resetSession()
        intervening = false
    }

    // ---------------- Feedback ----------------

    private fun playChime() {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
        handler.postDelayed({ tone.startTone(ToneGenerator.TONE_PROP_BEEP, 300) }, 1000)
        handler.postDelayed({ tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 500) }, 2000)
        handler.postDelayed({ tone.release() }, 3200)
    }

    private fun vibrate() {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
    }

    @SuppressLint("MissingPermission")
    private fun notifyUser(reason: String) {
        val nm = NotificationManagerCompat.from(this)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "LoopGuard", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("TikTok stopped")
            .setContentText(reason)
            .setAutoCancel(true)
            .build()
        try { nm.notify(1001, n) } catch (_: SecurityException) { /* notification permission denied */ }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(screenOff) } catch (_: IllegalArgumentException) {}
        super.onDestroy()
    }
}
