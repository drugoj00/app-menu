package app.tvapps

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 9.0 (код 45): уборка за собой. Временные файлы (скачанные для установки APK, root-install-*.apk, передачи другим установщикам) живут в cache/batch и cache/root-install-*.
 * Метод: после установки (успех, отказ или отмена) файл удаляется сразу; всё, что осталось (процесс убит заменой самой программы), подметается при запуске:
 * старше часа либо старше последнего обновления самой программы. Копии версий: одно место (корень из AppBackups.root), не больше двух на приложение.
 */
object Housekeeping {
    private const val MAX_AGE_MS = 60 * 60 * 1000L

    private fun tempItems(ctx: Context): List<File> {
        val out = ArrayList<File>()
        File(ctx.cacheDir, "batch").listFiles()?.let { out += it }
        ctx.cacheDir.listFiles { f -> f.name.startsWith("root-install-") }?.let { out += it }
        return out
    }

    private fun sizeOf(f: File): Long = if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Подметание при запуске: старше часа либо оставшееся от предыдущей версии программы. */
    fun sweep(ctx: Context) {
        val now = System.currentTimeMillis()
        val upd = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        var n = 0
        for (f in tempItems(ctx)) {
            val m = f.lastModified()
            if (now - m > MAX_AGE_MS || m < upd) { runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() }; n++ }
        }
        if (n > 0) Installers.note(ctx, "уборка: удалено временных элементов $n")
    }

    fun tempSize(ctx: Context): Long = tempItems(ctx).sumOf { sizeOf(it) }

    fun clearTemp(ctx: Context) { tempItems(ctx).forEach { runCatching { if (it.isDirectory) it.deleteRecursively() else it.delete() } } }

    /** Запуск при старте процесса: подметание, объединение мест хранения копий и ротация (в фоне), тост об объединении. */
    fun onStart(app: Context) {
        Thread {
            runCatching { sweep(app) }
            val moved = runCatching { AppBackups.unify(app) }.getOrDefault(0)
            if (moved > 0) Handler(Looper.getMainLooper()).post { Toast.makeText(app, app.getString(R.string.bk_merged), Toast.LENGTH_LONG).show() }
        }.apply { isDaemon = true; name = "housekeeping" }.start()
    }

    fun mb(b: Long): String = String.format(java.util.Locale.US, "%.1f", b / 1048576.0) + " MB"
}

/** Строка «Очистить копии и временные файлы» с размером и окном подтверждения. */
@Composable
fun CleanCopiesRow() {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var sizes by remember { mutableStateOf(Pair(0L, 0L)) }
    var ask by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(tick) {
        sizes = withContext(Dispatchers.IO) { AppBackups.totalSize(ctx) to Housekeeping.tempSize(ctx) }
    }
    val total = sizes.first + sizes.second
    ActionRow(HIcons.trash, stringResource(R.string.clean_row), stringResource(R.string.clean_row_h, Housekeeping.mb(total), Housekeeping.mb(sizes.first), Housekeeping.mb(sizes.second))) {
        tick++; ask = true
    }
    if (ask) HDialog(onDismissRequest = { ask = false }) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.clean_confirm_title), color = t.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.clean_confirm_text, Housekeeping.mb(total)), color = t.sub, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip(stringResource(R.string.cancel), false) { ask = false }
                Chip(stringResource(R.string.clean_do), true) {
                    ask = false
                    scope.launch {
                        val freed = withContext(Dispatchers.IO) {
                            val before = AppBackups.totalSize(ctx) + Housekeeping.tempSize(ctx)
                            AppBackups.clearAll(ctx); Housekeeping.clearTemp(ctx)
                            before
                        }
                        Toast.makeText(ctx, ctx.getString(R.string.clean_done, Housekeeping.mb(freed)), Toast.LENGTH_LONG).show()
                        tick++
                    }
                }
            }
        }
    }
}
