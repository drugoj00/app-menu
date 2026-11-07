package app.tvapps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Просьба открыть окно «Обновления» (из уведомления): главный экран подхватывает и сбрасывает. */
object UpdOpen {
    var requested by mutableStateOf(false)
    const val EXTRA = "open_updates"
}

/**
 * Проверка обновлений по расписанию в фоне. Без WorkManager и служб Google: системный JobScheduler (Android 5+),
 * задание переживает перезагрузку (setPersisted) и повторно ставится при старте процесса и после загрузки устройства.
 * Сеть только когда задание запускается. Найденное показывается уведомлением и числом в шапке; автоматическая установка —
 * только если отдельно включена и есть root/ADB (подпись, процессор и размер проверяет Updates.install, как при ручной установке).
 */
object UpdSched {
    private const val JOB_ID = 4711
    private const val CHANNEL = "upd"
    private const val NOTIF_ID = 4712
    private const val LOG_MAX = 200

    /** Ставит или снимает расписание по настройкам. Безопасно вызывать сколько угодно раз. */
    fun apply(ctx: Context) {
        runCatching {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            if (!Cfg.autoUpd) { js.cancel(JOB_ID); return }
            val every = Cfg.autoUpdHours * 3600_000L
            val cur = js.allPendingJobs.firstOrNull { it.id == JOB_ID }
            if (cur != null && cur.isPeriodic && cur.intervalMillis == every) return
            val b = JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdJobService::class.java))
                .setPeriodic(every)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
            js.schedule(b.build())
        }.onFailure { log(ctx, "расписание не поставлено: ${it.message}") }
    }

    // ---------------------------------------------------------------- журнал

    private fun logFile(ctx: Context) = File(ctx.filesDir, "upd_auto.log")

    fun log(ctx: Context, text: String) {
        runCatching {
            val f = logFile(ctx)
            val line = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(Date()) + "  " + text
            val old = if (f.exists()) f.readLines() else emptyList()
            f.writeText((old + line).takeLast(LOG_MAX).joinToString("\n") + "\n")
        }
    }

    // ---------------------------------------------------------------- уведомления

    private fun manager(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.upd_notif_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        return nm
    }

    private fun notify(ctx: Context, title: String, text: String) {
        runCatching {
            val nm = manager(ctx)
            val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(UpdOpen.EXTRA, true)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getActivity(ctx, 4712, open, flags)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(ctx)
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
            nm.notify(NOTIF_ID, b.build())
        }.onFailure { log(ctx, "уведомление не показано: ${it.message}") }
    }

    // ---------------------------------------------------------------- проверка

    /** Запоминает итог проверки (число для шапки и «ключ» набора). Зовётся и из окна «Обновления». */
    fun record(list: List<AppUpd>) {
        val upd = list.filter { it.best != null && it.pkg !in Cfg.updSkip }
        Cfg.setUpdFound(upd.size, upd.sortedBy { it.pkg }.joinToString(",") { it.pkg + ":" + it.best!!.versionName })
    }

    /** Одна фоновая проверка. Работает в потоке задания; сеть и установка тут же. */
    suspend fun run(ctx: Context) {
        Cfg.init(ctx)
        if (!Cfg.autoUpd) return
        if (!Integrity.safe(ctx)) return
        log(ctx, "проверка начата (источники: " + Updates.ALL_SOURCES.filter { it in Cfg.updSources }.joinToString(",") + ")")
        val res = Updates.checkAll(ctx, force = false) { _, _ -> }
        val upd = res.filter { it.best != null && it.pkg !in Cfg.updSkip }
        log(ctx, "проверено ${res.size}, обновления: ${upd.size}" + if (upd.isNotEmpty()) " (" + upd.joinToString(", ") { it.label + " " + it.instName + "→" + it.best!!.versionName } + ")" else "")
        val done = ArrayList<AppUpd>()
        if (Cfg.autoInstall && upd.isNotEmpty()) {
            if (!Adb.isConnected) runCatching { autoConnect(ctx) }
            if (!Adb.isConnected) log(ctx, "автоустановка пропущена: нет root/ADB")
            else for (a in upd) {
                val c = a.best ?: continue
                val r = runCatching { Updates.install(ctx, a, c) { } }.getOrElse { "fail:" + (it.message ?: "") }
                log(ctx, "установка ${a.label} ${a.instName}→${c.versionName} [${c.source}]: $r")
                if (r == "ok") done.add(a)
            }
        }
        val left = upd.filter { a -> done.none { it.pkg == a.pkg } }
        val key = left.sortedBy { it.pkg }.joinToString(",") { it.pkg + ":" + it.best!!.versionName }
        Cfg.setUpdFound(left.size, key)
        if (done.isNotEmpty()) notify(ctx, ctx.getString(R.string.upd_notif_installed, done.size), done.joinToString(", ") { it.label })
        if (left.isNotEmpty() && key != Cfg.updNotifiedKey()) {
            notify(ctx, ctx.getString(R.string.upd_notif_found, left.size), left.take(8).joinToString("\n") { it.label + ": " + it.instName + " → " + it.best!!.versionName } + if (left.size > 8) "\n…" else "")
            Cfg.setUpdNotifiedKey(key)
        }
        if (left.isEmpty()) Cfg.setUpdNotifiedKey("")
        log(ctx, "проверка закончена")
    }
}

/** Задание JobScheduler: запускает UpdSched.run в отдельном потоке. */
class UpdJobService : JobService() {
    @Volatile private var thread: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        thread = Thread {
            try {
                runBlocking { UpdSched.run(app) }
            } catch (e: Throwable) {
                UpdSched.log(app, "ошибка: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                runCatching { jobFinished(params, false) }
            }
        }.also { it.start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // система остановила задание (нет сети и т. п.): повторить в следующем окне
        return true
    }
}
