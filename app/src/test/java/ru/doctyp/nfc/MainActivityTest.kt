package ru.doctyp.nfc

import android.app.Application
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNfcAdapter
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
    private lateinit var app: Application
    @Before fun clean() {
        app = RuntimeEnvironment.getApplication(); app.filesDir.deleteRecursively(); app.filesDir.mkdirs()
    }
    private fun texts(view: View): List<TextView> =
        (if (view is TextView) listOf(view) else emptyList()) +
        (if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())
    private fun views(activity: MainActivity) = texts(activity.window.decorView)
    private fun button(activity: MainActivity, prefix: String) = views(activity).filterIsInstance<Button>().first { it.text.startsWith(prefix) }
    private fun setupNfc(enabled: Boolean) {
        ShadowNfcAdapter.setNfcHardwareExists(true)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_NFC, true)
        shadowOf(NfcAdapter.getDefaultAdapter(app)).setEnabled(enabled)
    }
    @Test fun absentNfcDisablesBothScans() {
        ShadowNfcAdapter.setNfcHardwareExists(false)
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertFalse(button(c.get(), "Начать").isEnabled)
        assertFalse(button(c.get(), "Отдельная").isEnabled)
        assertTrue(views(c.get()).any { it.text.contains("На устройстве нет NFC") })
        c.pause().stop().destroy()
    }
    @Test fun disabledNfcPromptsSettingsAndDisablesScan() {
        setupNfc(false)
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertFalse(button(c.get(), "Начать").isEnabled)
        assertTrue(views(c.get()).any { it.text.contains("Включите NFC") })
        c.pause().stop().destroy()
    }
    @Test fun repeatScanDoesNotInheritPreviousDeadlineAndPauseStopsReader() {
        setupNfc(true)
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
        button(a, "Начать").performClick()
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        button(a, "Остановить").performClick(); button(a, "Начать").performClick()
        shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.SECONDS)
        assertTrue(button(a, "Остановить").isEnabled)
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        assertFalse(button(a, "Остановить").isEnabled)
        assertFalse(ReportStore(a).last()!!.getBoolean("detected"))
        assertTrue(views(a).any { it.text.contains("За 20 секунд метка не обнаружена") })
        button(a, "Начать").performClick(); c.pause()
        assertFalse(shadowOf(NfcAdapter.getDefaultAdapter(a)).isInReaderMode)
        assertTrue(ReportStore(a).last()!!.getString("status").contains("прервано"))
        c.stop().destroy()
    }
}
