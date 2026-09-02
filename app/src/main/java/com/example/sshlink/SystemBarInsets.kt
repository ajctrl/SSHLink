package com.example.sshlink

import android.os.Build
import android.view.View
import android.view.WindowInsets
import kotlin.math.max

/** Applies system-bar and display-cutout safe insets without adding AndroidX. */
object SystemBarInsets {
    fun apply(view: View) {
        val baseLeft = view.paddingLeft
        val baseTop = view.paddingTop
        val baseRight = view.paddingRight
        val baseBottom = view.paddingBottom

        view.setOnApplyWindowInsetsListener { v, insets ->
            val safe = if (Build.VERSION.SDK_INT >= 30) {
                val values = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                Insets(values.left, values.top, values.right, values.bottom)
            } else if (Build.VERSION.SDK_INT >= 28) {
                @Suppress("DEPRECATION")
                val cutout = insets.displayCutout
                @Suppress("DEPRECATION")
                Insets(
                    max(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0),
                    max(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0),
                    max(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0),
                    max(insets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0),
                )
            } else {
                @Suppress("DEPRECATION")
                Insets(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            v.setPadding(
                baseLeft + safe.left,
                baseTop + safe.top,
                baseRight + safe.right,
                baseBottom + safe.bottom,
            )
            insets
        }
        view.requestApplyInsets()
    }

    private data class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int)
}
