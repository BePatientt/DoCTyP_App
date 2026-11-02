package ru.doctyp.nfc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

object Reports {
    fun new(mode: String) = JSONObject().apply {
        put("schemaVersion", 1); put("reportId", UUID.randomUUID().toString())
        put("name", "Офисный брелок"); put("timestamp", Instant.now().toString()); put("mode", mode)
        put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        put("android", android.os.Build.VERSION.RELEASE); put("sdk", android.os.Build.VERSION.SDK_INT)
        put("detected", false); put("status", "Сканирование начато"); put("actions", JSONArray())
        put("limits", "Чтение не равно эмуляции. UID может быть случайным. HCE не позволяет задавать произвольный UID или эмулировать произвольный брелок. Нужны сведения о системе и, возможно, отдельный мобильный credential. Отсутствие обнаружения не определяет частоту и исправность.")
    }
    fun action(r: JSONObject, name: String, status: String, detail: String, error: Throwable? = null) {
        r.getJSONArray("actions").put(JSONObject().apply {
            put("action", name); put("status", status); put("detail", detail)
            if (error != null) put("exception", error.javaClass.name)
        })
    }
    fun text(r: JSONObject): String = buildString {
        appendLine("DoCTyP · NFC — ${r.optString("name")}")
        val labels = mapOf("timestamp" to "Дата (UTC)", "device" to "Устройство", "android" to "Android", "sdk" to "API", "mode" to "Режим", "detected" to "Метка обнаружена", "status" to "Результат", "idHex" to "Идентификатор (HEX)", "idLength" to "Длина идентификатора (байт)", "technologies" to "Технологии", "capabilities" to "Возможности устройства")
        for (key in listOf("timestamp", "device", "android", "sdk", "mode", "detected", "status", "idHex", "idLength", "technologies", "capabilities"))
            if (r.has(key)) appendLine("${labels[key]}: ${r.get(key)}")
        val actions = r.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until actions.length()) {
            val a = actions.getJSONObject(i)
            appendLine("\n${a.optString("action")} [${a.optString("status")}]: ${a.optString("detail")}")
            if (a.has("exception")) appendLine(a.getString("exception"))
        }
        appendLine("\nОграничения: ${r.optString("limits")}")
    }
}

class ReportStore(context: Context) {
    private val directory = File(context.filesDir, "reports").apply { mkdirs() }
    private val current = File(context.filesDir, "last-report.json")
    private fun write(file: File, report: JSONObject) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(report.toString(2))
        check(temp.renameTo(file)) { "Не удалось сохранить отчёт" }
    }
    fun remember(report: JSONObject) = write(current, report)
    fun last(): JSONObject? = if (current.exists()) JSONObject(current.readText()) else null
    fun save(report: JSONObject) = write(File(directory, report.getString("reportId") + ".json"), report)
    fun list(): List<JSONObject> = directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }?.mapNotNull {
        runCatching { JSONObject(it.readText()) }.getOrNull()
    } ?: emptyList()
    fun delete(report: JSONObject) {
        val id = report.getString("reportId")
        check(File(directory, "$id.json").let { !it.exists() || it.delete() })
        if (last()?.optString("reportId") == id) current.delete()
    }
}
