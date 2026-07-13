package com.makskbz.myvpnproject.vpn

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.lang.reflect.Method
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ProtectSocketServer — v3.8 CIS-MAX SUPER-BYPASS
 *
 * КРИТИЧЕСКИЙ АРХИТЕКТУРНЫЙ ПРОБЕЛ, найденный при анализе жалобы
 * пользователя "meduza.io недоступен, хотя сторонний ByeDPI на том же
 * телефоне открывает": наш нативный движок ciadpi/byedpi открывает
 * СОБСТВЕННЫЕ исходящие TCP-сокеты к реальным серверам (remote_sock() в
 * byedpi/proxy.c) НАПРЯМУЮ из процесса приложения — а процесс приложения
 * целиком находится "внутри" VPN (весь его трафик маршрутизируется через
 * TUN, см. addRoute("0.0.0.0", 0) в BypassVpnService). Без явной защиты
 * эти исходящие сокеты САМИ попадают обратно в TUN, создавая петлю
 * TUN → tun2socks → SOCKS5 → ciadpi → (новое соединение) → TUN → ...
 * которая либо виснет, либо отбрасывается системой — сайты либо не
 * открываются вовсе, либо открываются только частично/нестабильно.
 *
 * Раньше единственным способом исключить процесс из VPN было
 * addDisallowedApplication(packageName) в BypassVpnService.runVpn() — но
 * на некоторых прошивках (в частности ColorOS/OxygenOS на OnePlus,
 * согласно отчёту пользователя) этого разрешения на уровне ПРИЛОЖЕНИЯ
 * недостаточно для КОНКРЕТНЫХ native file descriptor'ов, открытых уже
 * ПОСЛЕ старта VPN — сам API Android для этого называется
 * VpnService.protect(int fd) и должен вызываться явно для каждого
 * исходящего сокета.
 *
 * Штатный способ вызвать protect() из C-кода (когда сам процесс уже
 * работает под VPN) — послать файловый дескриптор через Unix domain
 * socket (SCM_RIGHTS ancillary message) в JVM-поток, который держит
 * ссылку на VpnService, и оттуда вызвать protect(fd) — именно так это
 * реализовано в апстримном byedpi (extend.c: protect(conn_fd, path) —
 * см. params.protect_path) и во всех Android-портах (xSocksAndroid,
 * ByeDPIAndroid, ByeByeDPI, hev-socks5-tunnel).
 *
 * Протокол (симметричен C-стороне в byedpi/extend.c: protect()):
 *  1. C-код подключается к AF_UNIX-сокету по пути protect_path.
 *  2. C-код шлёт 1 байт данных + ancillary SCM_RIGHTS с исходным fd.
 *  3. Мы принимаем через LocalSocket.getAncillaryFileDescriptors(),
 *     вызываем VpnService.protect(fd), закрываем нашу копию fd (сам
 *     оригинальный fd в C-процессе остаётся рабочим — Unix ancillary
 *     передача дублирует дескриптор, а не перемещает его).
 *  4. Отвечаем 1 байтом (успех/неуспех) — C-сторона ждёт этот байт с
 *     таймаутом 1с (см. connect()/recv() в byedpi extend.c: protect()).
 */
object ProtectSocketServer {

    private const val TAG = "ProtectSocketServer"

    @Volatile private var serverSocket: LocalServerSocket? = null
    @Volatile private var boundLocalSocket: LocalSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var running = false
    private var executor: ExecutorService? = null

    // Reflection-метод для извлечения numeric fd из java.io.FileDescriptor —
    // публичного API для этого нет (getInt$() — package-private аксессор
    // самого Android framework), но это тот же самый приём, что используют
    // xSocksAndroid/ByeDPIAndroid и другие открытые реализации того же
    // протокола — стабилен на всех версиях Android с момента появления
    // LocalSocket.getAncillaryFileDescriptors() (API 1).
    private val getIntMethod: Method? by lazy {
        try {
            FileDescriptor::class.java.getDeclaredMethod("getInt$").apply { isAccessible = true }
        } catch (e: Exception) {
            Log.e(TAG, "FileDescriptor.getInt$() reflection unavailable: ${e.message}")
            null
        }
    }

    /**
     * Запускает сервер на заданном абсолютном пути файловой системы
     * (обычно context.filesDir/protect_path или noBackupFilesDir — важно,
     * чтобы путь был доступен на запись/чтение процессу приложения и не
     * пересекался с другими экземплярами). Путь передаётся в C-код через
     * ciadpi_jni.c (params.protect_path).
     *
     * @return true если сервер успешно поднялся и готов принимать запросы
     */
    fun start(path: String, vpnService: VpnService): Boolean {
        if (running) {
            Log.i(TAG, "already running")
            return true
        }
        try {
            // Удаляем старый файл сокета, если остался от предыдущего
            // (некорректно завершённого) запуска — bind() иначе провалится
            // с "Address already in use".
            try { File(path).delete() } catch (_: Exception) {}

            val localSocket = LocalSocket()
            localSocket.bind(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            boundLocalSocket = localSocket
            val srv = LocalServerSocket(localSocket.fileDescriptor)
            serverSocket = srv

            executor = Executors.newFixedThreadPool(4)
            running = true

            acceptThread = Thread({
                Log.i(TAG, "listening on $path")
                CrashLogger.checkpoint(vpnService, "ProtectSocketServer: listening on $path")
                while (running) {
                    val client = try {
                        srv.accept()
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "accept() failed: ${e.message}")
                        break
                    }
                    executor?.execute { handleClient(client, vpnService) }
                }
                Log.i(TAG, "accept loop finished")
            }, "ProtectSocketServer").apply { isDaemon = true; start() }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "start() failed: ${e.message}", e)
            try { CrashLogger.checkpoint(vpnService, "ProtectSocketServer: start() FAILED ${e.javaClass.name}: ${e.message}") } catch (_: Exception) {}
            stop()
            return false
        }
    }

    private fun handleClient(socket: LocalSocket, vpnService: VpnService) {
        try {
            val input = socket.inputStream
            val output = socket.outputStream
            // Читаем 1 маркерный байт, отправленный C-кодом ВМЕСТЕ с
            // ancillary-сообщением (см. byedpi/extend.c: protect() —
            // sendmsg() с io.iov_base="1"). Ancillary-данные доступны
            // ТОЛЬКО после операции чтения обычных данных того же сообщения.
            val marker = input.read()
            if (marker < 0) {
                Log.w(TAG, "client closed before sending data")
                return
            }
            val fds = socket.ancillaryFileDescriptors
            if (fds.isNullOrEmpty()) {
                Log.w(TAG, "no ancillary fd received")
                output.write(0)
                return
            }
            val fd = fds[0]
            val getInt = getIntMethod
            val nativeFd = if (getInt != null) {
                try { getInt.invoke(fd) as Int } catch (e: Exception) {
                    Log.e(TAG, "getInt$ invoke failed: ${e.message}")
                    -1
                }
            } else -1

            val ok = if (nativeFd >= 0) {
                try {
                    vpnService.protect(nativeFd)
                } catch (e: Exception) {
                    Log.e(TAG, "protect($nativeFd) threw: ${e.message}")
                    false
                }
            } else false

            output.write(if (ok) 1 else 0)
        } catch (e: Exception) {
            Log.e(TAG, "handleClient error: ${e.message}", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { boundLocalSocket?.close() } catch (_: Exception) {}
        executor?.shutdownNow()
        acceptThread?.interrupt()
        serverSocket = null
        boundLocalSocket = null
        acceptThread = null
        executor = null
        Log.i(TAG, "stopped")
    }
}
