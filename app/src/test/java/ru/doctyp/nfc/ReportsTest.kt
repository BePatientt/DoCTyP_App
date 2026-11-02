package ru.doctyp.nfc

import android.app.Application
import android.nfc.TagLostException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReportsTest {
    private lateinit var app: Application
    @Before fun clean() {
        app = RuntimeEnvironment.getApplication()
        app.filesDir.deleteRecursively(); app.filesDir.mkdirs()
    }
    @Test fun partialReportSurvivesLossSaveReloadAndExports() {
        val r = Reports.new("NDEF_CHECK").apply {
            put("detected", true); put("idHex", "01AB"); put("idLength", 2)
        }
        Reports.action(r, "NDEF", "TAG_LOST", "Связь потеряна", TagLostException())
        val store = ReportStore(app); store.remember(r); store.save(r)
        val saved = ReportStore(app).list().single()
        assertEquals("01AB", saved.getString("idHex"))
        assertEquals("TAG_LOST", saved.getJSONArray("actions").getJSONObject(0).getString("status"))
        assertTrue(Reports.text(saved).contains("Связь потеряна"))
        assertTrue(Reports.text(saved).contains("android.nfc.TagLostException"))
        assertEquals(2, JSONObject(saved.toString(2)).getInt("idLength"))
        assertEquals(r.getString("reportId"), ReportStore(app).last()!!.getString("reportId"))
    }
    @Test fun renameUpdatesRatherThanDuplicatesAndDeleteRemovesLast() {
        val r = Reports.new("BASIC_SKIP_NDEF"); val store = ReportStore(app)
        store.save(r); r.put("name", "Мой офисный брелок"); store.save(r); store.remember(r)
        assertEquals("Мой офисный брелок", store.list().single().getString("name"))
        store.delete(r); assertTrue(store.list().isEmpty()); assertNull(store.last())
    }
    @Test fun noTagReportDoesNotClaimDetectionOrFrequency() {
        val r = Reports.new("BASIC_SKIP_NDEF")
        r.put("status", "За 20 секунд метка не обнаружена")
        assertFalse(r.getBoolean("detected")); assertFalse(r.has("idHex"))
        assertTrue(Reports.text(r).contains("Отсутствие обнаружения не определяет частоту"))
    }
}
