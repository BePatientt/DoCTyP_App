# Основания реализации

Проверены официальные Javadoc из пакета Google SDK `sources;android-35`, API 35 (Android 15), полученного SDK Manager 2 ноября 2026. Страницы developer.android.com из текущей среды отвечали 403 на CONNECT прокси; нельзя утверждать, что их актуальная веб-редакция была просмотрена. Домен внесён в черновик настроек сети для последующей проверки.

Проверенные источники в SDK:

- `android/nfc/NfcAdapter.java`, `enableReaderMode`: только foreground Activity; Reader Mode отключает card emulation; `FLAG_READER_SKIP_NDEF_CHECK` запрещает системную NDEF-проверку и перечисление Ndef. Поэтому базовая и NDEF-сессии разделены.
- `android/nfc/Tag.java`, `getId`, `getTechList`: идентификатор может меняться или быть нулевой длины, порядок технологий не определён; наличие API технологии отличается от доступа к данным.
- `android/nfc/tech/TagTechnology.java`: cached getters не требуют соединения и не создают RF-обмена; MifareClassic, MifareUltralight, NfcBarcode — опциональны для устройства. Отсутствие интерфейса не устанавливает точную модель чипа.
- `android/nfc/tech/Ndef.java`: `getNdefMessage` выполняет блокирующее I/O, допускает null, TagLostException, IOException, FormatException, SecurityException. Выполняется вне UI-потока, соединение закрывается; отмена закрывает соединение из другого потока. Содержание NDEF-записей не экспортируется.
- `android/nfc/cardemulation/HostApduService.java`: HCE обрабатывает ISO-DEP APDU с маршрутизацией по AID. Это не универсальная эмуляция всех NFC-технологий. Приложение не регистрирует HCE-сервис.

Ссылки для сверки:

- https://developer.android.com/reference/android/nfc/NfcAdapter#enableReaderMode(android.app.Activity,android.nfc.NfcAdapter.ReaderCallback,int,android.os.Bundle)
- https://developer.android.com/reference/android/nfc/Tag
- https://developer.android.com/reference/android/nfc/tech/TagTechnology
- https://developer.android.com/reference/android/nfc/tech/Ndef
- https://developer.android.com/develop/connectivity/nfc/hce
- https://developer.android.com/about/versions/15/behavior-changes-all

Используются только публичные API, NFC permission и runtime feature checks. Target SDK 35: системные отступы edge-to-edge обработаны WindowInsets; регистрация receiver учитывает export flags; обмен файлами — FileProvider с временным разрешением чтения; сохранение — Storage Access Framework без доступа ко всему хранилищу. INTERNET permission отсутствует. Стандартный Android не даёт этому приложению задавать произвольный UID или воспроизводить MifareClassic/LF-пропуск через HostApduService.

Нельзя определить по форме брелока или сообщению другого приложения его частоту, протокол либо исправность. Нельзя определить офисный механизм авторизации по одному UID или IsoDep. Для этапа Б могут потребоваться модель считывателя/контроллера, поддержка мобильных credentials, используемый протокол/AID и выпущенные системой полномочия. Никакие ключи не подбираются.
