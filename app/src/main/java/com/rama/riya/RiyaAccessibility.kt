package com.rama.riya

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 1) Presses WhatsApp's Send button, only for ~20 seconds after Riya opens a prefilled chat.
 * 2) Wakes the screen and enters your saved PIN/password on the lock screen (voice unlock).
 */
class RiyaAccessibility : AccessibilityService() {

    companion object {
        @Volatile var armedUntil = 0L
        @Volatile var instance: RiyaAccessibility? = null
        fun arm() { armedUntil = System.currentTimeMillis() + 20000 }
    }

    private val worker = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() { instance = this }

    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }

    override fun onDestroy() { instance = null; worker.shutdownNow(); super.onDestroy() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (System.currentTimeMillis() > armedUntil) return
        val pkg = event?.packageName?.toString() ?: return
        if (!pkg.startsWith("com.whatsapp")) return
        val root = rootInActiveWindow ?: return
        val ids = listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty() && click(nodes[0])) { armedUntil = 0; return }
        }
        val byText = root.findAccessibilityNodeInfosByText("Send")
        if (byText.isNotEmpty() && click(byText[0])) armedUntil = 0
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = n
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = node.parent
        }
        return false
    }

    override fun onInterrupt() {}

    // ---------------- voice unlock ----------------

    /** Runs on a background thread; [done] is told whether the phone ended up unlocked. */
    fun unlock(secret: String, done: (Boolean) -> Unit) {
        worker.execute {
            val ok = try { runUnlock(secret) } catch (e: Exception) { false }
            done(ok)
        }
    }

    private fun isUnlocked() = !(getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    private fun isLockPkg(root: AccessibilityNodeInfo): Boolean {
        val p = root.packageName?.toString() ?: return false
        return p.contains("systemui") || p.contains("keyguard")
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "riya:wake"
        )
        wl.acquire(10000)
    }

    private fun runUnlock(secret: String): Boolean {
        if (isUnlocked()) return true
        wakeScreen()
        Thread.sleep(600)
        swipeUp()
        val digitsOnly = secret.all { it.isDigit() }
        val deadline = System.currentTimeMillis() + 7000
        var swipes = 1
        var lastSwipe = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null && isLockPkg(root)) {
                if (digitsOnly && findKey(root, secret[0]) != null) return typePin(secret)
                if (!digitsOnly) {
                    val field = findEditable(root)
                    if (field != null) return typePassword(field, secret)
                }
            }
            if (swipes < 3 && System.currentTimeMillis() - lastSwipe > 2000) {
                swipeUp(); swipes++; lastSwipe = System.currentTimeMillis()
            }
            Thread.sleep(250)
        }
        return isUnlocked()
    }

    private fun typePin(pin: String): Boolean {
        for (ch in pin) {
            val root = rootInActiveWindow ?: return false
            val key = findKey(root, ch) ?: return false
            tap(key)
            Thread.sleep(160)
        }
        val root = rootInActiveWindow
        if (root != null) findEnter(root)?.let { tap(it) }
        return waitUnlocked()
    }

    private fun typePassword(field: AccessibilityNodeInfo, secret: String): Boolean {
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, secret)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Thread.sleep(250)
        if (Build.VERSION.SDK_INT >= 30) field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        return waitUnlocked()
    }

    private fun waitUnlocked(): Boolean {
        val end = System.currentTimeMillis() + 2500
        while (System.currentTimeMillis() < end) {
            if (isUnlocked()) return true
            Thread.sleep(200)
        }
        return false
    }

    // A keypad digit: by Android's own id (key0..key9), else by its text / description
    private fun findKey(root: AccessibilityNodeInfo, d: Char): AccessibilityNodeInfo? {
        val s = d.toString()
        root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/key$d").firstOrNull()?.let { return it }
        val hits = root.findAccessibilityNodeInfosByText(s).filter {
            (it.text?.toString() == s || it.contentDescription?.toString() == s) &&
                (it.packageName?.toString()?.let { p -> p.contains("systemui") || p.contains("keyguard") } == true)
        }
        return hits.firstOrNull { it.isClickable || it.parent?.isClickable == true } ?: hits.firstOrNull()
    }

    private fun findEnter(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/key_enter").firstOrNull()?.let { return it }
        for (label in listOf("Enter", "OK", "Done")) {
            val hit = root.findAccessibilityNodeInfosByText(label).firstOrNull {
                val t = it.text?.toString() ?: it.contentDescription?.toString() ?: ""
                t.equals(label, ignoreCase = true)
            }
            if (hit != null) return hit
        }
        return null
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) return node
        for (i in 0 until node.childCount) findEditable(node.getChild(i))?.let { return it }
        return null
    }

    private fun tap(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        val p = Path().apply { moveTo(r.centerX().toFloat(), r.centerY().toFloat()) }
        return gesture(p, 60)
    }

    private fun swipeUp() {
        val dm = resources.displayMetrics
        val p = Path().apply {
            moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.85f)
            lineTo(dm.widthPixels / 2f, dm.heightPixels * 0.25f)
        }
        gesture(p, 300)
    }

    private fun gesture(path: Path, duration: Long): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        val sent = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
        }, null)
        if (!sent) return false
        latch.await(2, TimeUnit.SECONDS)
        return ok
    }
}
