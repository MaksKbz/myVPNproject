package com.makskbz.myvpnproject.vpn

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashLogger — v3.7.3 CIS-MAX
 *
 * Пользователь тестирует APK на реальном устройстве без доступа к
 * `adb logcat`, поэтому диагностика краша "вслепую" через код невозможна —
 * нужен способ увидеть причину прямо в приложении. Этот объект перехватывает
 * необработанные Kotlin/Java-исключения (Thread.UncaughtExceptionHandler) и
 * сохраняет их в файл внутри приложения (переживает краш процесса, т.к.
 * пишется на диск синхронно до завершения процесса). Нативные сигналы
 * (SIGABRT/SIGSEGV/...) перехватываются отдельно в C-коде — см.
 * ciadpi_jni.c: installNativeCrashHandler() — и пишутся в отдельный файл тем
 * же способом (async-signal-safe write() в уже открытый fd).
 *
 * После следующего запуска MainActivity проверяет оба файла и показывает
 * их содержимое прямо в UI с кнопкой "Скопировать" — пользователь может
 * прислать текст в чат без ADB.
 */
object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val JAVA_CRASH_FILE = "crash_log_java.txt"
    const val NATIVE_CRASH_FILE = "crash_log_native.txt"
    private const val CHECKPOINT_FILE = "crash_log_checkpoints.txt"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Volatile private var installed = false

    /**
     * Устанавливает перехватчик необработанных Kotlin/Java-исключений.
     * Вызывать как можно раньше — из Application.onCreate().
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val entry = buildString {
                    append("=== JAVA/KOTLIN CRASH ===\n")
                    append("Время: ${dateFormat.format(Date())}\n")
                    append("Поток: ${thread.name}\n")
                    append(sw.toString())
                    append("\n")
                }
                appendToFile(appContext, JAVA_CRASH_FILE, entry)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to write crash log", e)
            }
            // Передаём управление системному обработчику — приложение всё
            // равно завершится, мы только успели сохранить причину на диск.
            previousHandler?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "Java/Kotlin uncaught exception handler installed")
    }

    private fun appendToFile(context: Context, fileName: String, text: String) {
        try {
            val f = File(context.filesDir, fileName)
            f.appendText(text)
        } catch (_: Exception) {
            // если даже запись на диск не удалась — ничего не поделать,
            // но это не должно помешать штатному завершению приложения.
        }
    }

    /**
     * v3.7.5 CIS-MAX: лёгкая контрольная точка на стороне Kotlin — пишется
     * НЕМЕДЛЕННО (RandomAccessFile.fd.sync() форсирует сброс на диск), в
     * отличие от обычного File.appendText(), которая теоретически может
     * быть отложена буферизацией ОС/файловой системы.
     *
     * Нужна для диагностики SIGKILL — сигнала, который система/прошивка
     * (например, агрессивный OEM watchdog вроде ColorOS/MIUI Autostart
     * Manager) может послать процессу напрямую, минуя любые перехватчики
     * (ни Thread.UncaughtExceptionHandler, ни нативный sigaction — оба
     * бессильны против SIGKILL). Единственный способ понять, ГДЕ именно
     * произошла смерть процесса — расставить частые контрольные точки и
     * посмотреть, какая из них была записана последней.
     */
    // Ограничение размера файла чекпоинтов, чтобы он не рос бесконечно
    // между сеансами (пользователь может много раз запускать/
    // останавливать VPN без единого краша) — при превышении просто
    // обрезаем файл и начинаем заново, самое важное всё равно последние
    // записи перед крахом.
    // v3.7.7: увеличено с 64KB — теперь есть быстрый (4 записи/сек)
    // мониторинг памяти в BypassVpnService.startFastMemoryWatch(),
    // который иначе исчерпывал бы старый лимит за ~100 секунд работы.
    private const val MAX_CHECKPOINT_FILE_SIZE = 256 * 1024L

    fun checkpoint(context: Context, tag: String) {
        try {
            val f = File(context.filesDir, CHECKPOINT_FILE)
            if (f.exists() && f.length() > MAX_CHECKPOINT_FILE_SIZE) {
                f.delete()
            }
            java.io.RandomAccessFile(f, "rw").use { raf ->
                raf.seek(raf.length())
                val line = "[kt-checkpoint t=${System.currentTimeMillis()}] $tag\n"
                raf.write(line.toByteArray(Charsets.UTF_8))
                raf.fd.sync()
            }
        } catch (_: Exception) {
            // не должно мешать основному потоку выполнения
        }
    }

    /** Читает лог Kotlin-контрольных точек, если файл существует и не пуст. */
    fun readCheckpointLog(context: Context): String? =
        readIfExists(context, CHECKPOINT_FILE)

    /** Возвращает путь к файлу нативных крашей — передаётся в JNI. */
    fun nativeCrashLogPath(context: Context): String =
        File(context.filesDir, NATIVE_CRASH_FILE).absolutePath

    /** Читает лог Kotlin/Java-крашей, если файл существует и не пуст. */
    fun readJavaCrashLog(context: Context): String? =
        readIfExists(context, JAVA_CRASH_FILE)

    /** Читает лог нативных (C/JNI) крашей, если файл существует и не пуст. */
    fun readNativeCrashLog(context: Context): String? =
        readIfExists(context, NATIVE_CRASH_FILE)

    private fun readIfExists(context: Context, fileName: String): String? {
        return try {
            val f = File(context.filesDir, fileName)
            if (f.exists() && f.length() > 0) f.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    fun clearAll(context: Context) {
        try { File(context.filesDir, JAVA_CRASH_FILE).delete() } catch (_: Exception) {}
        try { File(context.filesDir, NATIVE_CRASH_FILE).delete() } catch (_: Exception) {}
        try { File(context.filesDir, CHECKPOINT_FILE).delete() } catch (_: Exception) {}
    }
}
