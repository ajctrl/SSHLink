package com.example.sshlink

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityLayoutTest {
    @Test fun manyForwardsKeepControlsVisibleInLandscapeWithLargeText() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            val forwards = MainActivity::class.java.getDeclaredField("forwardsView")
                .apply { isAccessible = true }.get(activity) as TextView
            forwards.textSize = 28f
            forwards.text = (1..ConfigLimits.MAX_FORWARDS).joinToString("\n") {
                "127.0.0.1:${8000 + it} → service.lan:443"
            }
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val density = activity.resources.displayMetrics.density
            root.measure(
                View.MeasureSpec.makeMeasureSpec((640 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            val buttons = descendants(root).filterIsInstance<Button>()
            assertEquals(setOf("Start", "Stop", "Settings"), buttons.map { it.text.toString() }.toSet())
            buttons.forEach { button ->
                val bounds = Rect(0, 0, button.width, button.height)
                (root as ViewGroup).offsetDescendantRectToMyCoords(button, bounds)
                assertTrue(button.height > 0)
                assertTrue(bounds.top >= root.paddingTop)
                assertTrue(bounds.bottom <= root.height - root.paddingBottom)
            }
        } finally {
            controller.destroy()
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()
}
