package ru.doctyp.nfc

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.*
import org.json.JSONArray
import org.json.JSONObject

object TagDiagnostics {
    private val active = java.util.concurrent.atomic.AtomicReference<Ndef?>(null)
    fun cancel() {
        val connection = active.getAndSet(null) ?: return
        Thread({ runCatching { connection.close() } }, "nfc-close").start()
    }
    private fun ByteArray?.hex(): String = this?.joinToString("") { "%02X".format(it.toInt() and 255) } ?: "не предоставлено"
    fun metadata(tag: Tag, report: JSONObject) {
        report.put("detected", true); report.put("status", "Метка обнаружена; сохранены метаданные")
        report.put("idHex", tag.id?.hex() ?: JSONObject.NULL)
        report.put("idLength", tag.id?.size ?: 0); report.put("technologies", JSONArray(tag.techList.toList()))
        Reports.action(report, "Tag.getTechList / Tag.getId", "OK", "Событие обнаружения получено от Android. Это не подтверждает доступ к защищённым данным.")
    }
    fun inspect(tag: Tag, r: JSONObject, ndefMode: Boolean, cancelled: () -> Boolean, update: (JSONObject) -> Unit) {
        var failures = false
        fun step(name: String, body: () -> String) {
            if (cancelled()) return
            try { Reports.action(r, name, "OK", body()) }
            catch (e: Exception) {
                failures = true
                val status = when(e) { is TagLostException -> "TAG_LOST"; is SecurityException -> "ACCESS_RESTRICTED"; is UnsupportedOperationException -> "UNSUPPORTED_DEVICE"; else -> "UNKNOWN_ERROR" }
                val detail = when(e) {
                    is TagLostException -> "Связь с меткой потеряна. Уже полученные сведения сохранены."
                    is UnsupportedOperationException -> "Телефон или Android не поддерживает эту операцию."
                    is SecurityException -> "Android ограничил доступ; это не доказывает наличие ключевой защиты брелока."
                    else -> "Ошибка обращения к технологии; причина не установлена. ${e.message.orEmpty()}"
                }
                Reports.action(r, name, status, detail, e)
            }
            update(JSONObject(r.toString()))
        }
        val names = tag.techList.toSet()
        val readers = linkedMapOf<String, () -> String>(
            NfcA::class.java.name to { NfcA.get(tag)!!.let { "ATQA=${it.atqa.hex()}, SAK=${it.sak.toInt() and 65535}, maxTransceiveLength=${it.maxTransceiveLength}" } },
            NfcB::class.java.name to { NfcB.get(tag)!!.let { "applicationData=${it.applicationData.hex()}, protocolInfo=${it.protocolInfo.hex()}, maxTransceiveLength=${it.maxTransceiveLength}" } },
            NfcF::class.java.name to { NfcF.get(tag)!!.let { "systemCode=${it.systemCode.hex()}, manufacturer=${it.manufacturer.hex()}, maxTransceiveLength=${it.maxTransceiveLength}" } },
            NfcV::class.java.name to { NfcV.get(tag)!!.let { "responseFlags=${it.responseFlags.toInt() and 255}, dsfId=${it.dsfId.toInt() and 255}, maxTransceiveLength=${it.maxTransceiveLength}" } },
            IsoDep::class.java.name to { IsoDep.get(tag)!!.let { "historicalBytes=${it.historicalBytes.hex()}, hiLayerResponse=${it.hiLayerResponse.hex()}, extendedLengthApdu=${it.isExtendedLengthApduSupported}, maxTransceiveLength=${it.maxTransceiveLength}" } },
            MifareClassic::class.java.name to { MifareClassic.get(tag)!!.let { "Доступен интерфейс Android; type=${it.type}, size=${it.size}, sectors=${it.sectorCount}, blocks=${it.blockCount}. Аутентификация и чтение секторов не выполнялись." } },
            MifareUltralight::class.java.name to { MifareUltralight.get(tag)!!.let { "Доступен интерфейс Android; type=${it.type}. Чтение страниц не выполнялось." } },
            NfcBarcode::class.java.name to { NfcBarcode.get(tag)!!.let { "type=${it.type}, barcode=${it.barcode.hex()}" } }
        )
        readers.forEach { (name, reader) ->
            if (name in names) step(name, reader)
            else Reports.action(r, name, "NOT_LISTED", "Интерфейс не перечислен Android. Это не позволяет отличить неподдерживаемый телефоном чип от отсутствия технологии у метки.")
        }
        if (!ndefMode) {
            Reports.action(r, "NDEF", "SKIPPED", "Базовый режим использует FLAG_READER_SKIP_NDEF_CHECK: наличие NDEF не определялось. Для проверки запустите отдельный режим и приложите брелок снова.")
        } else {
            val ndef = Ndef.get(tag)
            if (ndef == null) {
                Reports.action(r, "NDEF", "NOT_LISTED", "Android не предоставил Ndef после системной проверки. Брелок не объявляется пустым или повреждённым.")
            } else step("NDEF") {
                try {
                    active.set(ndef)
                    if (cancelled()) throw java.util.concurrent.CancellationException("Сессия завершена")
                    ndef.connect()
                    val message = ndef.ndefMessage
                    "type=${ndef.type}, maxSize=${ndef.maxSize}, writable=${ndef.isWritable}, canMakeReadOnly=${ndef.canMakeReadOnly()}, records=${message?.records?.size ?: 0}, bytes=${message?.toByteArray()?.size ?: 0}. Содержимое записей не сохраняется; запись не выполнялась."
                } finally { active.compareAndSet(ndef, null); ndef.close() }
            }
            if (NdefFormatable::class.java.name in names) Reports.action(r, "NdefFormatable", "AVAILABLE", "Интерфейс доступен; форматирование НЕ выполняется.")
        }
        r.put("status", if (failures) "Доступны отдельные характеристики; есть ошибки (см. действия)" else "Диагностика завершена; доступные характеристики сохранены")
        update(JSONObject(r.toString()))
    }
}
