package app.tvapps

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver

/** Заглушка службы ADB-phon (только debug): читает файл по content-адресу, шлёт ход и «установлено». */
class DbgInstallService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    @Suppress("DEPRECATION")
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val rr = i?.getParcelableExtra<ResultReceiver>(AdbPhoneSilent.EXTRA_RESULT)
        val size = runCatching { i?.data?.let { contentResolver.openInputStream(it)?.use { s -> s.readBytes().size } } }.getOrNull() ?: -1
        val h = Handler(Looper.getMainLooper())
        rr?.send(2, Bundle().apply { putString("message", "started $size") })
        h.postDelayed({ rr?.send(1, Bundle().apply { putString("message", "50") }) }, 1500)
        h.postDelayed({ rr?.send(0, Bundle().apply { putString("version", "stub") }); stopSelf() }, 3500)
        return START_NOT_STICKY
    }
}
