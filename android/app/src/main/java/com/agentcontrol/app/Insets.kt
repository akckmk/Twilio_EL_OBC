package com.agentcontrol.app

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Keeps content clear of the status bar and gesture bar (Android 15+ draws edge to edge). */
fun View.padForSystemBars() {
    val start = paddingLeft; val top = paddingTop; val end = paddingRight; val bottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        v.setPadding(start + bars.left, top + bars.top, end + bars.right, bottom + bars.bottom)
        insets
    }
}

/**
 * Call before setContentView in every screen.
 * - Blocks screenshots, screen recording and the preview in Recent apps.
 * - Hides other apps' floating windows over ours, so nobody can trick a tap (Android 12+).
 */
fun Activity.hardenWindow() {
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
}
