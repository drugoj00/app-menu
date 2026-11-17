@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.tvapps

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Окно сведений о пакете ПЕРЕД установкой собственными способами программы (ADB и системная сессия), 8.1.
 * Показывается ВСЕГДА, и при запомненном выборе установщика: запоминается только способ установки, но не пропуск окна.
 * Установка через ADB-phon и другие установщики идёт передачей файла: окно сведений показывает сам тот установщик.
 */
object InstallInfo {
    class Info(
        val name: String, val icon: Drawable?, val pkg: String, val newName: String, val newCode: Long,
        val oldName: String?, val oldCode: Long, val sizeMb: String, val sig: Int, val downgrade: Boolean, val parts: Int, val note: String = "",
    )

    class Req(val info: Info, val done: CompletableDeferred<Boolean>)

    /** Открытый запрос подтверждения; рисуется [InstallInfoHost]. */
    var req by mutableStateOf<Req?>(null)

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    private fun certs(pi: PackageInfo?): Set<String> {
        pi ?: return emptySet()
        val out = HashSet<String>()
        if (Build.VERSION.SDK_INT >= 28) {
            pi.signingInfo?.let { si ->
                si.apkContentsSigners?.forEach { out.add(sha(it.toByteArray())) }
                if (!si.hasMultipleSigners()) si.signingCertificateHistory?.forEach { out.add(sha(it.toByteArray())) }
            }
        }
        pi.signatures?.forEach { out.add(sha(it.toByteArray())) }
        return out
    }

    @Suppress("DEPRECATION")
    private fun code(pi: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()

    /** Сведения из файла APK (у набора частей берётся первый файл) и сравнение с установленным: подпись 1 совпадает, 0 нет, -1 не прочитана или нет установленного. */
    @Suppress("DEPRECATION")
    fun describe(ctx: Context, files: List<File>, fallbackName: String): Info {
        val pm = ctx.packageManager
        val flags = (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0) or PackageManager.GET_SIGNATURES
        val f = files.firstOrNull()
        val ai = f?.let { runCatching { pm.getPackageArchiveInfo(it.path, flags) }.getOrNull() }
        val pkg = ai?.packageName.orEmpty()
        var label = fallbackName
        var icon: Drawable? = null
        if (ai?.applicationInfo != null && f != null) {
            ai.applicationInfo!!.sourceDir = f.path; ai.applicationInfo!!.publicSourceDir = f.path
            label = runCatching { ai.applicationInfo!!.loadLabel(pm).toString() }.getOrDefault(fallbackName).ifBlank { fallbackName }
            icon = runCatching { ai.applicationInfo!!.loadIcon(pm) }.getOrNull()
        }
        val inst = if (pkg.isNotEmpty()) runCatching { pm.getPackageInfo(pkg, flags) }.getOrNull() else null
        val newCode = ai?.let { code(it) } ?: 0L
        val sig = when {
            inst == null || ai == null -> -1
            else -> { val a = certs(ai); val b = certs(inst); if (a.isEmpty() || b.isEmpty()) -1 else if (a.intersect(b).isNotEmpty()) 1 else 0 }
        }
        val mb = "%.1f".format(java.util.Locale.US, files.sumOf { it.length() } / 1048576.0)
        return Info(label, icon, pkg, ai?.versionName.orEmpty(), newCode, inst?.versionName, inst?.let { code(it) } ?: 0L, mb, sig, inst != null && newCode < code(inst), files.size)
    }

    /** Показать окно и дождаться «Установить» (true) или «Отмена» (false). */
    suspend fun confirm(ctx: Context, files: List<File>, fallbackName: String, note: String = ""): Boolean {
        val info = withContext(Dispatchers.IO) { describe(ctx.applicationContext, files, fallbackName) }.let { if (note.isEmpty()) it else Info(it.name, it.icon, it.pkg, it.newName, it.newCode, it.oldName, it.oldCode, it.sizeMb, it.sig, it.downgrade, it.parts, note) }
        val d = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) { req = Req(info, d) }
        return try { d.await() } finally { withContext(Dispatchers.Main) { req = null } }
    }
}

private fun drawableBitmap(d: Drawable): android.graphics.Bitmap {
    val b = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    d.setBounds(0, 0, 96, 96); d.draw(c)
    return b
}

@Composable
fun InstallInfoHost() {
    val r = InstallInfo.req ?: return
    val i = r.info
    val t = LocalTh.current
    val go = remember(r) { FocusRequester() }
    LaunchedEffect(r) { kotlinx.coroutines.delay(150); runCatching { go.requestFocus() } }
    val sc = rememberScrollState()
    HDialog(onDismissRequest = { r.done.complete(false) }) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(t.tile).border(1.dp, t.border, RoundedCornerShape(20.dp)).verticalScroll(sc).padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val bmp = remember(r) { runCatching { i.icon?.let { d -> drawableBitmap(d).asImageBitmap() } }.getOrNull() }
                if (bmp != null) Image(bmp, null, Modifier.size(48.dp))
                Column {
                    Text(i.name, color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    if (i.pkg.isNotEmpty()) Text(i.pkg, color = t.hint, fontSize = 12.sp, maxLines = 1)
                }
            }
            val newV = i.newName.ifBlank { "?" } + " (" + i.newCode + ")"
            Text(
                if (i.oldName != null) stringResource(R.string.inst_info_installed, i.oldName + " (" + i.oldCode + ")") + "\n" + stringResource(R.string.inst_info_new, newV)
                else stringResource(R.string.inst_info_fresh) + "\n" + stringResource(R.string.inst_info_new, newV),
                color = t.text, fontSize = 15.sp,
            )
            Text(stringResource(R.string.inst_info_size, i.sizeMb) + if (i.parts > 1) " · " + stringResource(R.string.inst_info_parts, i.parts) else "", color = t.sub, fontSize = 14.sp)
            when (i.sig) {
                1 -> Text(stringResource(R.string.inst_sig_ok), color = t.accent, fontSize = 14.sp)
                0 -> Text(stringResource(R.string.inst_sig_bad), color = androidx.compose.ui.graphics.Color(0xFFF59E0B), fontSize = 14.sp)
                else -> if (i.oldName != null) Text(stringResource(R.string.inst_sig_unknown), color = t.hint, fontSize = 14.sp)
            }
            if (i.note.isNotEmpty()) Text(i.note, color = androidx.compose.ui.graphics.Color(0xFFF59E0B), fontSize = 13.sp)
            if (i.downgrade) Text(stringResource(R.string.inst_downgrade), color = androidx.compose.ui.graphics.Color(0xFFF59E0B), fontSize = 14.sp)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionRow(HIcons.download, stringResource(R.string.inst_go), "", Modifier.focusRequester(go)) { r.done.complete(true) }
                Chip(stringResource(R.string.cancel), false) { r.done.complete(false) }
            }
        }
    }
}
