package ru.doctyp.nfc

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val session = ScanSession()
    @Volatile private var token = 0L
    private var adapter: NfcAdapter? = null
    private lateinit var store: ReportStore
    private var report: JSONObject? = null
    private lateinit var content: LinearLayout
    private lateinit var hardware: TextView
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var start: Button
    private lateinit var ndef: Button
    private lateinit var stop: Button
    private lateinit var save: Button
    private lateinit var export: Button
    private lateinit var library: Button
    private var resumed = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (adapter?.isEnabled != true && session.active()) stopScan("NFC выключен; полученные сведения сохранены")
            refresh()
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        adapter = (getSystemService(NFC_SERVICE) as? NfcManager)?.defaultAdapter
        store = ReportStore(this)
        report = runCatching { store.last() }.getOrNull()
        report?.let {
            if (it.optString("status") == "Сканирование начато" || it.optString("status").startsWith("Метка обнаружена;"))
                it.put("status", "Предыдущая сессия прервана; сохранён доступный результат")
        }
        buildScreen()
        registerReceiver(receiver, IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED), RECEIVER_EXPORTED)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float = 16f): TextView = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Color.rgb(24, 43, 38)); setPadding(0, dp(6), 0, dp(6))
    }
    private fun button(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text; isAllCaps = false; minHeight = dp(48); setOnClickListener { action() }
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }
    private fun buildScreen() {
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Color.rgb(243, 246, 244)) }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(12), dp(22), dp(24)) }
        scroll.addView(content)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        content.addView(label("DoCTyP", 30f).apply { typeface = Typeface.DEFAULT_BOLD })
        content.addView(label("ДИАГНОСТИКА NFC · 01", 12f))
        hardware = label(""); content.addView(hardware)
        button("Настройки NFC") {
            try { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
            catch (_: ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
        }
        content.addView(label("Разблокируйте телефон. Приложите брелок к задней стороне, медленно перемещайте по верхней и центральной части и удерживайте 2–3 секунды. Если не обнаружен — снимите чехол и попробуйте снова."))
        status = label("Готово к сканированию", 18f).apply { typeface = Typeface.DEFAULT_BOLD }
        content.addView(status)
        start = button("Начать сканирование · 20 с") { begin(false) }
        ndef = button("Отдельная проверка NDEF · 20 с") { begin(true) }
        content.addView(label("Для NDEF уберите брелок, нажмите кнопку и приложите снова. Это отдельный отчёт; отсутствие NDEF не означает повреждение.", 13f))
        stop = button("Остановить") { stopScan("Сканирование остановлено пользователем"); refresh() }
        content.addView(label("Последний результат", 21f))
        result = label("Пока нет отчётов", 14f).apply {
            setTextIsSelectable(true); setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat() }
        }; content.addView(result)
        save = button("Сохранить / переименовать") { report?.let { rename(it) } }
        export = button("Экспорт / Поделиться") { exportChoice() }
        library = button("Сохранённые отчёты") { showLibrary() }
        content.addView(label("Только чтение · Без сервера и аккаунта\nПриложение не записывает и не форматирует пропуск. Идентификатор не является копией пропуска. Возможность прохода пока не установлена.", 13f))
        setContentView(scroll)
        refresh()
    }
    override fun onResume() { super.onResume(); resumed = true; refresh() }
    override fun onPause() {
        if (session.active()) stopScan("Сканирование прервано уходом с экрана; доступен частичный результат")
        resumed = false
        super.onPause()
    }
    override fun onDestroy() { unregisterReceiver(receiver); worker.shutdownNow(); handler.removeCallbacksAndMessages(null); super.onDestroy() }
    private fun capabilities() = JSONObject().apply {
        put("nfcFeature", packageManager.hasSystemFeature(PackageManager.FEATURE_NFC))
        put("adapterPresent", adapter != null); put("enabled", adapter?.isEnabled == true)
        put("hceIsoDep", packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION))
        put("hceNfcF", packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF))
        put("mifareHardwareSupport", "Стандартный API не предоставляет надёжного общего запроса; интерфейсы проверяются для обнаруженного Tag.")
    }
    private fun refresh() {
        val a = adapter
        hardware.text = "NFC: ${if (a == null) "отсутствует" else if (a.isEnabled) "включён" else "выключен"}\nЧтение: ${if (a != null && packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)) "заявлено Android; конкретные технологии — после сканирования" else "не заявлено"}\nHCE (ISO-DEP): ${if (packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) "поддерживается" else "не поддерживается"}; совместимость с офисом неизвестна"
        val busy = session.active()
        start.isEnabled = a?.isEnabled == true && !busy
        ndef.isEnabled = start.isEnabled; stop.isEnabled = busy
        save.isEnabled = report != null && !busy; export.isEnabled = save.isEnabled; library.isEnabled = !busy
        result.text = report?.let(Reports::text) ?: "Пока нет отчётов"
        status.text = if (busy) report?.optString("status") else when {
            a == null -> "На устройстве нет NFC"
            !a.isEnabled -> "Включите NFC в настройках"
            else -> report?.optString("status") ?: "Готово к сканированию"
        }
    }
    private fun persist(r: JSONObject) { runCatching { store.remember(r) }.onFailure { toast("Ошибка локального сохранения: ${it.javaClass.simpleName}") } }
    private fun begin(ndefMode: Boolean) {
        val a = adapter ?: return
        if (!resumed || !a.isEnabled || session.active()) { refresh(); return }
        token = session.start(); val currentToken = token
        val r = Reports.new(if (ndefMode) "NDEF_CHECK" else "BASIC_SKIP_NDEF")
        r.put("capabilities", capabilities()); report = r; persist(r); refresh()
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_BARCODE or
            (if (ndefMode) 0 else NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK)
        try {
            a.enableReaderMode(this, { tag ->
                handler.post {
                    if (!session.detected(currentToken)) return@post
                    TagDiagnostics.metadata(tag, r); report = JSONObject(r.toString()); persist(report!!); refresh()
                    handler.postDelayed({
                        if (token == currentToken && session.state == ScanSession.State.DETECTED) {
                            TagDiagnostics.cancel()
                            report?.let { it.put("status", "Превышено время дополнительной проверки; частичные данные сохранены"); persist(it) }
                            session.cancel(); token = -1; disableReader(); refresh()
                        }
                    }, 8_000)
                    worker.execute {
                        try {
                            TagDiagnostics.inspect(tag, r, ndefMode, { token != currentToken }) { snapshot ->
                                handler.post {
                                    if (token == currentToken && session.state == ScanSession.State.DETECTED) {
                                        report = snapshot; persist(snapshot); refresh()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Reports.action(r, "Диагностика", "UNKNOWN_ERROR", "Частичный результат сохранён", e)
                            r.put("status", "Ошибка диагностики; доступен частичный результат")
                        }
                        handler.post {
                            if (session.complete(currentToken)) {
                                report = JSONObject(r.toString()); persist(report!!); disableReader(); refresh()
                            }
                        }
                    }
                }
            }, flags, Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) })
            handler.postDelayed({
                if (session.timeout(currentToken)) {
                    r.put("status", "За 20 секунд метка не обнаружена")
                    Reports.action(r, "Обнаружение", "NO_TAG", "Событие обнаружения не получено. Причина неизвестна: положение, NFC, несовместимая технология или другие факторы. Частота и исправность не определены.")
                    report = r; persist(r); disableReader(); refresh()
                }
            }, 20_000)
        } catch (e: Exception) {
            Reports.action(r, "enableReaderMode", "ERROR", "Не удалось включить режим чтения", e)
            stopScan("Ошибка запуска Reader Mode"); refresh()
        }
    }
    private fun disableReader() { runCatching { adapter?.disableReaderMode(this) } }
    private fun stopScan(reason: String) {
        session.cancel(); token = -1; TagDiagnostics.cancel(); disableReader()
        report?.let { it.put("status", reason); persist(it) }
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun rename(r: JSONObject) {
        val input = EditText(this).apply { setSingleLine(); setText(r.optString("name")); filters = arrayOf(android.text.InputFilter.LengthFilter(100)) }
        AlertDialog.Builder(this).setTitle("Название отчёта").setView(input).setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                r.put("name", input.text.toString().trim().ifEmpty { "Офисный брелок" })
                runCatching { store.save(r); store.remember(r) }.onSuccess { toast("Сохранено локально") }.onFailure { toast("Ошибка сохранения: ${it.javaClass.simpleName}") }
                refresh()
            }.show()
    }
    private fun showLibrary() {
        val entries = store.list()
        if (entries.isEmpty()) { toast("Сохранённых отчётов пока нет"); return }
        AlertDialog.Builder(this).setTitle("Сохранённые отчёты")
            .setItems(entries.map { "${it.optString("name")}\n${it.optString("timestamp")}" }.toTypedArray()) { _, i ->
                val selected = entries[i]
                AlertDialog.Builder(this).setTitle(selected.optString("name"))
                    .setItems(arrayOf("Открыть", "Переименовать", "Удалить")) { _, choice ->
                        when(choice) {
                            0 -> { report = selected; persist(selected); refresh() }
                            1 -> { report = selected; rename(selected) }
                            2 -> AlertDialog.Builder(this).setMessage("Удалить этот отчёт с устройства?").setNegativeButton("Отмена", null).setPositiveButton("Удалить") { _, _ ->
                                runCatching { store.delete(selected); File(cacheDir, "exports").deleteRecursively() }
                                    .onSuccess { if (report?.optString("reportId") == selected.optString("reportId")) report = null; refresh() }
                                    .onFailure { toast("Не удалось удалить отчёт") }
                            }.show()
                        }
                    }.show()
            }.setNegativeButton("Закрыть", null).show()
    }
    private fun exportChoice() {
        AlertDialog.Builder(this).setTitle("Формат отчёта")
            .setItems(arrayOf("Текст (.txt)", "JSON (.json)")) { _, which ->
                AlertDialog.Builder(this).setTitle("Экспорт содержит идентификатор метки")
                    .setMessage("Передавайте отчёт только выбранному получателю. Автоматической отправки нет.")
                    .setNegativeButton("Отмена", null).setPositiveButton("Продолжить") { _, _ ->
                        val r = report ?: return@setPositiveButton
                        val json = which == 1
                        val mime = if (json) "application/json" else "text/plain"
                        val data = if (json) r.toString(2) else Reports.text(r)
                        AlertDialog.Builder(this).setItems(arrayOf("Поделиться", "Сохранить в файл")) { _, operation ->
                            if (operation == 0) share(data, json) else {
                                pendingExport = data
                                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE); type = mime
                                    putExtra(Intent.EXTRA_TITLE, "doctyp-report.${if (json) "json" else "txt"}")
                                }, 42)
                            }
                        }.show()
                    }.show()
            }.show()
    }
    private var pendingExport: String? = null
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putString("pendingExport", pendingExport) }
    override fun onRestoreInstanceState(savedInstanceState: Bundle) { super.onRestoreInstanceState(savedInstanceState); pendingExport = savedInstanceState.getString("pendingExport") }
    @Deprecated("Legacy activity result is sufficient for this single Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42) {
            if (resultCode == RESULT_OK) runCatching {
                val uri = requireNotNull(data?.data); val text = requireNotNull(pendingExport)
                requireNotNull(contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(text) }
            }.onSuccess { toast("Файл сохранён") }.onFailure { toast("Ошибка экспорта: ${it.javaClass.simpleName}") }
            pendingExport = null
        }
    }
    private fun share(data: String, json: Boolean) {
        runCatching {
            val folder = File(cacheDir, "exports").apply { deleteRecursively(); mkdirs() }
            val file = File(folder, "doctyp-report.${if (json) "json" else "txt"}").apply { writeText(data) }
            val uri = FileProvider.getUriForFile(this, "$packageName.reports", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = if (json) "application/json" else "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri); clipData = ClipData.newRawUri("NFC отчёт", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Поделиться отчётом"))
        }.onFailure { toast("Ошибка отправки: ${it.javaClass.simpleName}") }
    }
}
