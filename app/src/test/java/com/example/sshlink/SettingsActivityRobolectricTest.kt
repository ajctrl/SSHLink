package com.example.sshlink

import android.os.Bundle
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsActivityRobolectricTest {
    @Test
    fun configurationChangePreservesUnsavedFieldsAndForwards() {
        val originalController = Robolectric.buildActivity(SettingsActivity::class.java)
            .create()
            .start()
            .resume()
        val original = originalController.get()
        editText(original, "hostEdit").setText("unsaved.example.com")
        SettingsActivity::class.java
            .getDeclaredMethod(
                "addForwardRow",
                String::class.java,
                String::class.java,
                String::class.java,
            )
            .apply { isAccessible = true }
            .invoke(original, "Office PC", "13389", "192.168.1.1:3389")

        val state = Bundle()
        originalController.saveInstanceState(state).pause().stop().destroy()

        val recreatedController = Robolectric.buildActivity(SettingsActivity::class.java)
            .create(state)
            .start()
            .resume()
        val recreated = recreatedController.get()

        assertEquals("unsaved.example.com", editText(recreated, "hostEdit").text.toString())
        val rows = field(recreated, "forwardRows") as List<*>
        assertEquals(1, rows.size)
        val row = requireNotNull(rows.single())
        assertEquals("Office PC", editText(row, "name").text.toString())
        assertEquals("13389", editText(row, "local").text.toString())
        assertEquals("192.168.1.1:3389", editText(row, "destination").text.toString())
        recreatedController.pause().stop().destroy()
    }

    private fun editText(instance: Any, name: String): EditText = field(instance, name) as EditText

    private fun field(instance: Any, name: String): Any? = instance.javaClass
        .getDeclaredField(name)
        .apply { isAccessible = true }
        .get(instance)
}
