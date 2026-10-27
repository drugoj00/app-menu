package app.tvapps

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.max

/** Состояние ADB-соединения для значка в шапке и пунктов меню. */
object Conn {
    var connected by mutableStateOf(false)
    var root by mutableStateOf(false)
    fun refresh() { connected = Adb.isConnected; root = Adb.isRoot }
    /** Строка состояния: «ADB: подключено», «Root: подключено» или «не подключено». */
    val statusRes: Int get() = if (!connected) R.string.adb_off else if (root) R.string.root_on else R.string.adb_on
}

private fun toast(ctx: android.content.Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

/** Цвета карточки: светлое матовое стекло с цветной полосой внизу, как у меню значков в One UI. */
private object Pop {
    val card = Color(0xF2EAF1F2)
    val band = Color(0xFFCFE3E2)
    val text = Color(0xFF1B1F23)
    val sub = Color(0xFF5B6770)
    val accent = Color(0xFF0F766E)
    val danger = Color(0xFFB91C1C)
    val focusFill = Color(0x662DD4BF)
    val focusLine = Color(0xFF0F766E)
}

// ---------- значки ----------

private fun outline(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
        fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
    ).build()

private val IconStop: ImageVector by lazy {
    outline("Stop") {
        moveTo(8f, 4f); lineTo(16f, 4f); lineTo(20f, 8f); lineTo(20f, 16f); lineTo(16f, 20f); lineTo(8f, 20f); lineTo(4f, 16f); lineTo(4f, 8f); close()
        moveTo(9f, 9f); lineTo(15f, 9f); lineTo(15f, 15f); lineTo(9f, 15f); close()
    }
}
private val IconBattery: ImageVector by lazy {
    outline("Battery") {
        moveTo(9f, 5f); lineTo(15f, 5f); lineTo(15f, 3.5f); lineTo(9f, 3.5f); close()
        moveTo(7f, 5f); lineTo(17f, 5f); lineTo(17f, 20.5f); lineTo(7f, 20.5f); close()
        moveTo(12.8f, 8.5f); lineTo(9.8f, 13.2f); lineTo(12f, 13.2f); lineTo(11.2f, 17.5f); lineTo(14.2f, 12.6f); lineTo(12f, 12.6f); close()
    }
}
private val IconSpecial: ImageVector by lazy {
    outline("Special") {
        moveTo(12f, 3f); lineTo(14.6f, 9.4f); lineTo(21f, 12f); lineTo(14.6f, 14.6f); lineTo(12f, 21f); lineTo(9.4f, 14.6f); lineTo(3f, 12f); lineTo(9.4f, 9.4f); close()
    }
}

// ---------- строительные блоки ----------

/** Кнопка «значок над подписью» в цветной полосе. */
@Composable
private fun ActionCell(icon: ImageVector, label: String, modifier: Modifier = Modifier, tint: Color = Pop.text, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val (hm, hover) = rememberHover()
    Column(
        modifier
            .then(hm)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused || hover) Pop.focusFill else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) Pop.focusLine else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.size(4.dp))
        Text(label, color = tint, fontSize = btnSp(11), fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Clip, lineHeight = btnSp(13))
    }
}

/** Широкая кнопка внутри карточки (подэкраны). */
@Composable
private fun PopButton(text: String, modifier: Modifier = Modifier, fill: Color = Color(0xFFDCE9E8), textColor: Color = Pop.text, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val (hm, hover) = rememberHover()
    Box(
        modifier.fillMaxWidth()
            .then(hm)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(14.dp))
            .background(if (!enabled) Color(0x22000000) else if (focused || hover) Pop.focusFill else fill)
            .border(if (focused) 2.dp else 0.dp, if (focused) Pop.focusLine else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, color = if (enabled) textColor else Pop.sub, fontSize = btnSp(15), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun ToggleRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val (hm, hover) = rememberHover()
    Row(
        Modifier.fillMaxWidth()
            .then(hm)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused || hover) Pop.focusFill else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) Pop.focusLine else Color.Transparent, RoundedCornerShape(12.dp))
            .toggleable(checked, onValueChange = onChange)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, color = Pop.text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Switch(checked, null)
    }
}

/** Стрелка-указатель на значок приложения. */
@Composable
private fun Tail(centerPx: Int, up: Boolean, color: Color) {
    val d = LocalDensity.current
    val w = 22.dp
    val startDp: Dp = with(d) { (centerPx.toFloat() / density).dp } - w / 2
    Canvas(Modifier.padding(start = max(0f, startDp.value).dp).size(w, 11.dp)) {
        val p = Path().apply {
            if (up) { moveTo(0f, size.height); lineTo(size.width / 2, 0f); lineTo(size.width, size.height) }
            else { moveTo(0f, 0f); lineTo(size.width / 2, size.height); lineTo(size.width, 0f) }
            close()
        }
        drawPath(p, color)
    }
}

/** Ставит карточку под значком (или над ним, если снизу нет места) и подсказывает, где рисовать стрелку. */
private class TailProvider(private val gap: Int, private val margin: Int) : PopupPositionProvider {
    var tailX by mutableIntStateOf(0)
    var below by mutableStateOf(true)
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2)
            .coerceIn(margin, max(margin, windowSize.width - popupContentSize.width - margin))
        val spaceBelow = windowSize.height - anchorBounds.bottom
        val isBelow = spaceBelow >= popupContentSize.height + gap || anchorBounds.top < popupContentSize.height
        val y = if (isBelow) anchorBounds.bottom + gap else anchorBounds.top - gap - popupContentSize.height
        tailX = anchorBounds.center.x - x
        below = isBelow
        return IntOffset(x, y.coerceIn(margin, max(margin, windowSize.height - popupContentSize.height - margin)))
    }
}

// ---------- само меню ----------

@Composable
fun AppMenu(e: Entry, hidden: Boolean, onToggleHide: () -> Unit, onSelect: () -> Unit, onMove: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val host = LocalHost.current
    var screen by remember { mutableStateOf("main") }
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(screen) { runCatching { first.requestFocus() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val ok = IconPacks.saveOwn(ctx, e.pkg, uri)
            toast(ctx, ctx.getString(if (ok) R.string.icon_saved else R.string.failed))
            Cfg.bumpIcons()
        }
        onClose()
    }
    val shortcuts by produceState(emptyList<Shortcut>(), e.pkg) { value = withContext(Dispatchers.Default) { Shortcuts.load(ctx, e) } }
    val provider = remember { TailProvider(with(density) { 6.dp.roundToPx() }, with(density) { 8.dp.roundToPx() }) }

    fun withAdb(block: suspend () -> Unit) {
        if (!Adb.isConnected) { screen = "connect"; return }
        scope.launch {
            busy = true
            try { block() } catch (t: Throwable) { toast(ctx, ctx.getString(R.string.failed)) } finally { busy = false }
        }
    }

    fun openAbout() {
        onClose()
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast(ctx, ctx.getString(R.string.no_settings_page)) }
    }

    Popup(popupPositionProvider = provider, onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        HalfUi {
        Column(Modifier.width(minOf(384f, screenDp().first - 16f).dp)) {
            if (provider.below) Tail(provider.tailX, up = true, color = Pop.card)
            Column(
                Modifier.clip(RoundedCornerShape(28.dp)).background(Pop.card).heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
            ) {
                // шапка: название по центру и кружок «i» справа, как в One UI
                Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                    Text(e.label, color = Pop.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 44.dp))
                    var infoFocused by remember { mutableStateOf(false) }
                    Box(
                        Modifier.align(Alignment.CenterEnd).size(40.dp)
                            .onFocusChanged { infoFocused = it.isFocused }
                            .clip(CircleShape)
                            .background(if (infoFocused) Pop.focusFill else Color.Transparent)
                            .border(if (infoFocused) 2.dp else 0.dp, if (infoFocused) Pop.focusLine else Color.Transparent, CircleShape)
                            .clickable { openAbout() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Info, stringResource(R.string.about), tint = Pop.text, modifier = Modifier.size(24.dp)) }
                }
                if (busy) Text(stringResource(R.string.working), color = Pop.accent, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp))

                if (screen == "main" && shortcuts.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                        shortcuts.forEach { sc -> ShortcutRow(sc, e) { onClose(); runCatching { ctx.startActivity(sc.intent) }.onFailure { toast(ctx, ctx.getString(R.string.cant_open)) } } }
                    }
                }
                when (screen) {
                    "main" -> Column(Modifier.fillMaxWidth().background(Pop.band).padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            ActionCell(IconStop, stringResource(R.string.stop), Modifier.weight(1f).focusRequester(first)) {
                                if (Adb.isConnected) withAdb {
                                    val ok = Actions.forceStop(e.pkg)
                                    toast(ctx, if (ok) ctx.getString(R.string.stopped, e.label) else ctx.getString(R.string.failed))
                                } else {
                                    runCatching {
                                        (ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).killBackgroundProcesses(e.pkg)
                                    }
                                    toast(ctx, ctx.getString(R.string.stopped_soft, e.label))
                                    onClose()
                                }
                            }
                            ActionCell(Icons.Outlined.Refresh, stringResource(R.string.clear_cache), Modifier.weight(1f)) {
                                withAdb { toast(ctx, if (Actions.clearCache(e.pkg)) ctx.getString(R.string.cache_cleared) else ctx.getString(R.string.failed)) }
                            }
                            ActionCell(Icons.Outlined.Delete, stringResource(R.string.clear_data), Modifier.weight(1f)) { screen = "confirm_data" }
                            ActionCell(Icons.Outlined.Lock, stringResource(R.string.perms), Modifier.weight(1f)) { screen = if (Adb.isConnected) "perms" else "connect" }
                        }
                        Row(Modifier.fillMaxWidth()) {
                            ActionCell(IconSpecial, stringResource(R.string.special), Modifier.weight(1f)) { screen = if (Adb.isConnected) "special" else "connect" }
                            ActionCell(IconBattery, stringResource(R.string.battery), Modifier.weight(1f)) { screen = if (Adb.isConnected) "battery" else "connect" }
                            ActionCell(if (hidden) IconEye else IconEyeOff, stringResource(if (hidden) R.string.unhide else R.string.hide), Modifier.weight(1f)) { onToggleHide(); onClose() }
                            ActionCell(IconRemove, stringResource(R.string.uninstall), Modifier.weight(1f), tint = Pop.danger) {
                                onClose()
                                if (e.system) toast(ctx, ctx.getString(R.string.system_no_delete))
                                else AppBackups.saveThen(ctx, listOf(e.pkg)) {
                                    runCatching { ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth()) {
                            ActionCell(Icons.Outlined.Create, stringResource(R.string.icon_cell), Modifier.weight(1f)) { screen = "icon" }
                            ActionCell(Icons.Outlined.Check, stringResource(R.string.select), Modifier.weight(1f)) { onSelect(); onClose() }
                            ActionCell(IconMove, stringResource(R.string.move), Modifier.weight(1f)) { onMove(); onClose() }
                            ActionCell(IconFolder, stringResource(R.string.folder_cell), Modifier.weight(1f)) { screen = "folder" }
                        }
                        Row(Modifier.fillMaxWidth()) {
                            ActionCell(IconVersions, stringResource(R.string.versions_cell), Modifier.weight(1f)) { onClose(); host?.openVersions(e) }
                            Spacer(Modifier.weight(3f))
                        }
                    }
                    else -> Column(Modifier.fillMaxWidth().background(Pop.band).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        when (screen) {
                            "confirm_data" -> {
                                Text(stringResource(R.string.confirm_data, e.label), color = Pop.text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 6.dp))
                                PopButton(stringResource(R.string.yes_clear), Modifier.focusRequester(first), fill = Color(0xFFFEE2E2), textColor = Pop.danger) {
                                    screen = "main"
                                    withAdb { toast(ctx, if (Actions.clearData(e.pkg)) ctx.getString(R.string.data_cleared) else ctx.getString(R.string.failed)) }
                                }
                                PopButton(stringResource(R.string.cancel)) { screen = "main" }
                            }
                            "icon" -> {
                                PopButton(stringResource(R.string.icon_pick), Modifier.focusRequester(first)) {
                                    runCatching { picker.launch("image/*") }.onFailure { toast(ctx, ctx.getString(R.string.icon_no_picker)) }
                                }
                                if (IconPacks.hasOwn(ctx, e.pkg)) PopButton(stringResource(R.string.icon_reset)) {
                                    IconPacks.removeOwn(ctx, e.pkg); Cfg.bumpIcons(); onClose()
                                }
                                Text(stringResource(R.string.icon_manual, IconPacks.manualDir(ctx)?.absolutePath.orEmpty(), e.pkg), color = Pop.sub, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp))
                                PopButton(stringResource(R.string.back_plain)) { screen = "main" }
                            }
                            "folder" -> {
                                val cur = Cfg.folderOf(e.pkg)
                                var attached = false
                                fun fr(): Modifier = if (!attached) { attached = true; Modifier.focusRequester(first) } else Modifier
                                if (cur != null) PopButton(stringResource(R.string.folder_remove, cur.name), fr()) { Cfg.removeFromFolder(e.pkg); onClose() }
                                Cfg.folders.filter { it.id != cur?.id }.forEach { f ->
                                    PopButton(stringResource(R.string.folder_move_to, f.name), fr()) { Cfg.addToFolder(f.id, listOf(e.pkg)); onClose() }
                                }
                                PopButton(stringResource(R.string.folder_new), fr()) {
                                    val name = ctx.getString(R.string.folder_default, Cfg.folders.size + 1)
                                    val id = Cfg.createFolder(name, listOf(e.pkg), e.pkg)
                                    onClose()
                                    host?.renameFolder(id)
                                }
                                PopButton(stringResource(R.string.back_plain)) { screen = "main" }
                            }
                            "perms" -> PermsPanel(e, first) { screen = "main" }
                            "special" -> SpecialPanel(e, first) { screen = "main" }
                            "battery" -> BatteryPanel(e, first) { screen = "main" }
                            "connect" -> ConnectPanel(first, onDone = { screen = "main" }, onBack = { screen = "main" })
                        }
                    }
                }
            }
            if (!provider.below) Tail(provider.tailX, up = false, color = Pop.band)
        }
        }
    }
}

/** Одна быстрая ссылка: круглый значок и подпись, как в One UI. */
@Composable
private fun ShortcutRow(sc: Shortcut, e: Entry, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bmp = remember(sc.id) { runCatching { (sc.icon ?: e.icon).toBitmap(72, 72).asImageBitmap() }.getOrNull() }
    val (hm, hover) = rememberHover()
    Row(
        Modifier.fillMaxWidth()
            .then(hm)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(16.dp))
            .background(if (focused || hover) Pop.focusFill else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) Pop.focusLine else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(Color(0xFFFFFFFF)), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(bmp, null, Modifier.size(24.dp))
        }
        Text(sc.label, color = Pop.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

private val IconEye: ImageVector by lazy {
    outline("Eye") {
        moveTo(2f, 12f); quadTo(12f, 3f, 22f, 12f); quadTo(12f, 21f, 2f, 12f); close()
        moveTo(15f, 12f)
        arcTo(3f, 3f, 0f, true, true, 9f, 12f)
        arcTo(3f, 3f, 0f, true, true, 15f, 12f)
        close()
    }
}
private val IconEyeOff: ImageVector by lazy {
    outline("EyeOff") {
        moveTo(2f, 12f); quadTo(12f, 3f, 22f, 12f); quadTo(12f, 21f, 2f, 12f); close()
        moveTo(4f, 4f); lineTo(20f, 20f)
    }
}

private val IconFolder: ImageVector by lazy {
    outline("Folder") {
        moveTo(3f, 6f); lineTo(10f, 6f); lineTo(12f, 8.5f); lineTo(21f, 8.5f); lineTo(21f, 19f); lineTo(3f, 19f); close()
    }
}
private val IconVersions: ImageVector by lazy {
    outline("Versions") {
        moveTo(12f, 3f); lineTo(21f, 8f); lineTo(12f, 13f); lineTo(3f, 8f); close()
        moveTo(3f, 12f); lineTo(12f, 17f); lineTo(21f, 12f)
        moveTo(3f, 16f); lineTo(12f, 21f); lineTo(21f, 16f)
    }
}

private val IconMove: ImageVector by lazy {
    outline("Move") {
        moveTo(12f, 3f); lineTo(12f, 21f); moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(9f, 6f); lineTo(12f, 3f); lineTo(15f, 6f)
        moveTo(9f, 18f); lineTo(12f, 21f); lineTo(15f, 18f)
        moveTo(6f, 9f); lineTo(3f, 12f); lineTo(6f, 15f)
        moveTo(18f, 9f); lineTo(21f, 12f); lineTo(18f, 15f)
    }
}

/** Значок «удалить» — кружок с чертой, как «⊖» у Samsung. */
private val IconRemove: ImageVector by lazy {
    outline("Remove") {
        moveTo(21f, 12f)
        arcTo(9f, 9f, 0f, true, true, 3f, 12f)
        arcTo(9f, 9f, 0f, true, true, 21f, 12f)
        close()
        moveTo(7.5f, 12f); lineTo(16.5f, 12f)
    }
}

@Composable
private fun BackRow(first: FocusRequester?, onBack: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val (hm, hover) = rememberHover()
    Row(
        (if (first != null) Modifier.focusRequester(first) else Modifier)
            .then(hm)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused || hover) Pop.focusFill else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) Pop.focusLine else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onBack)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.ArrowBack, null, tint = Pop.text, modifier = Modifier.size(22.dp))
        Text(stringResource(R.string.back).removePrefix("← "), color = Pop.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PermsPanel(e: Entry, first: FocusRequester, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var perms by remember { mutableStateOf<List<Pair<String, Boolean>>?>(null) }
    LaunchedEffect(Unit) { perms = runCatching { Actions.runtimePerms(e.pkg) }.getOrDefault(emptyList()) }
    BackRow(first, onBack)
    val list = perms
    if (list == null) Text(stringResource(R.string.working), color = Pop.accent, modifier = Modifier.padding(8.dp))
    else if (list.isEmpty()) Text(stringResource(R.string.perms_none), color = Pop.sub, modifier = Modifier.padding(8.dp))
    else list.forEach { (perm, on) ->
        ToggleRow(perm.substringAfterLast('.'), on) { v ->
            scope.launch {
                if (Actions.setPerm(e.pkg, perm, v)) perms = perms?.map { if (it.first == perm) perm to v else it }
                else toast(ctx, ctx.getString(R.string.failed))
            }
        }
    }
}

@Composable
private fun SpecialPanel(e: Entry, first: FocusRequester, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val labels = mapOf(
        "overlay" to R.string.sp_overlay, "settings" to R.string.sp_settings, "usage" to R.string.sp_usage,
        "files" to R.string.sp_files, "install" to R.string.sp_install, "restricted" to R.string.sp_restricted,
    )
    val accComp = remember { Actions.serviceOf(ctx, e.pkg, "android.accessibilityservice.AccessibilityService") }
    val notifComp = remember { Actions.serviceOf(ctx, e.pkg, "android.service.notification.NotificationListenerService") }
    var states by remember { mutableStateOf<Map<String, Boolean>?>(null) }
    suspend fun load() {
        val m = LinkedHashMap<String, Boolean>()
        for ((id, op) in Actions.appOps) m[id] = runCatching { Actions.opAllowed(e.pkg, op) }.getOrDefault(false)
        if (accComp != null) m["access"] = runCatching { Actions.accessibilityOn(accComp) }.getOrDefault(false)
        if (notifComp != null) m["notif"] = runCatching { Actions.notifOn(notifComp) }.getOrDefault(false)
        states = m
    }
    LaunchedEffect(Unit) { load() }
    BackRow(first, onBack)
    val st = states
    if (st == null) Text(stringResource(R.string.working), color = Pop.accent, modifier = Modifier.padding(8.dp))
    else {
        st.forEach { (id, on) ->
            val title = when (id) {
                "access" -> stringResource(R.string.sp_access)
                "notif" -> stringResource(R.string.sp_notif)
                else -> stringResource(labels.getValue(id))
            }
            ToggleRow(title, on) { v ->
                scope.launch {
                    val ok = when (id) {
                        "access" -> Actions.setAccessibility(accComp!!, v)
                        "notif" -> Actions.setNotif(notifComp!!, v)
                        else -> Actions.setOp(e.pkg, Actions.appOps.first { it.first == id }.second, v)
                    }
                    if (!ok) toast(ctx, ctx.getString(R.string.failed))
                    load()
                }
            }
        }
        if (accComp != null) ToggleRow(stringResource(R.string.no_restore), Cfg.isNoRestore(accComp)) { Cfg.toggleNoRestore(accComp) }
        if (accComp == null && notifComp == null) Text(stringResource(R.string.sp_no_services), color = Pop.sub, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
    }
}

@Composable
private fun BatteryPanel(e: Entry, first: FocusRequester, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Actions.Battery?>(null) }
    suspend fun load() { state = runCatching { Actions.batteryState(ctx, e.pkg) }.getOrNull() }
    LaunchedEffect(Unit) { load() }
    BackRow(first, onBack)
    Text(stringResource(R.string.batt_hint), color = Pop.sub, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
    listOf(
        Actions.Battery.UNRESTRICTED to R.string.batt_unrestricted,
        Actions.Battery.OPTIMIZED to R.string.batt_optimized,
        Actions.Battery.RESTRICTED to R.string.batt_restricted,
    ).forEach { (mode, label) ->
        val mark = if (state == mode) "✓  " else ""
        PopButton(mark + stringResource(label), fill = if (state == mode) Color(0xFFBFE6E1) else Color(0xFFDCE9E8)) {
            scope.launch {
                if (!Actions.setBattery(e.pkg, mode)) toast(ctx, ctx.getString(R.string.failed))
                load()
            }
        }
    }
}

@Composable
private fun ConnectPanel(first: FocusRequester, onDone: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pairPort by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun run(block: suspend () -> Result<Unit>) {
        scope.launch {
            busy = true
            val r = block()
            busy = false
            Conn.refresh()
            if (r.isSuccess && Adb.isConnected) { toast(ctx, ctx.getString(R.string.adb_ok)); onDone() }
            else toast(ctx, ctx.getString(R.string.adb_fail))
        }
    }
    LaunchedEffect(Unit) { if (!Adb.isConnected && Cfg.useRoot && Adb.tryRoot()) { Conn.refresh(); toast(ctx, ctx.getString(R.string.adb_ok)); onDone() } }
    // пока окно открыто и подключения нет, программа сама пробует порт 5555: стоит один раз включить сетевой ADB (с компьютера или по беспроводной отладке), и подключение произойдёт без нажатий
    LaunchedEffect(Unit) {
        while (!Adb.isConnected) {
            if (!busy) {
                Adb.connectLocal(5555)
                if (!Adb.isConnected && android.os.Build.VERSION.SDK_INT >= 30) Adb.connectWireless(ctx, null)
                Conn.refresh()
                if (Adb.isConnected) { toast(ctx, ctx.getString(R.string.adb_ok)); onDone(); break }
            }
            kotlinx.coroutines.delay(3500)
        }
        if (Adb.isConnected) { Conn.refresh(); onDone() }
    }
    BackRow(first, onBack)
    Text(stringResource(Conn.statusRes), color = if (Conn.connected) Pop.accent else Pop.sub, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
    PopButton(stringResource(R.string.root_connect_btn), enabled = !busy) {
        run { if (Adb.tryRoot()) Result.success(Unit) else Result.failure(IllegalStateException("no root")) }
    }
    Text(stringResource(R.string.root_hint), color = Pop.text, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
    if (android.os.Build.VERSION.SDK_INT < 30) Text(stringResource(R.string.connect_text_old), color = Pop.text, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
    if (busy) Text(stringResource(R.string.working), color = Pop.accent, modifier = Modifier.padding(horizontal = 8.dp))
    if (android.os.Build.VERSION.SDK_INT < 30) {
        // Android 10 и старше: «беспроводной отладки» нет, сетевой ADB включается один раз командой с компьютера
        Text(stringResource(R.string.old_android_hint), color = Pop.text, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
        PopButton(stringResource(R.string.copy_tcpip)) {
            (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("adb", "adb tcpip 5555"))
            toast(ctx, ctx.getString(R.string.copied))
        }
    }
    // Android 11+: порт сопряжения ищется сам, код вводится в уведомлении, не уходя с экрана настроек
    val sdk30 = android.os.Build.VERSION.SDK_INT >= 30
    DisposableEffect(Unit) {
        if (sdk30) Pairing.start(ctx)
        onDispose { if (!Adb.isConnected) Pairing.cancel(ctx); Pairing.stop() }
    }
    var autoOn by remember { mutableStateOf(AutoPairService.isEnabled(ctx)) }
    LaunchedEffect(Unit) { while (true) { autoOn = AutoPairService.isEnabled(ctx); kotlinx.coroutines.delay(1000) } }
    fun openWireless(withNotification: Boolean = true) {
        Pairing.start(ctx)
        android.util.Log.i("TVApps", "openWireless, notification=$withNotification")
        if (withNotification) Pairing.showInput(ctx)
        val tries = listOf(
            Intent("android.settings.ADB_WIRELESS_SETTINGS"),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$WirelessDebuggingActivity"),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        )
        var ok = false
        for (i in tries) { if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) { ok = true; break } }
        if (!ok) toast(ctx, ctx.getString(R.string.no_settings_page))
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { openWireless() }
    if (sdk30) {
        // полностью автоматически: служба спецвозможностей сама проходит по настройкам и читает код (включается один раз)
        Text(stringResource(R.string.autopair_hint), color = Pop.text, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
        if (!autoOn) PopButton(stringResource(R.string.autopair_enable)) {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        PopButton(stringResource(R.string.autopair_run), enabled = autoOn) { Pairing.autoStart(); openWireless(false) }
        PopButton(stringResource(R.string.open_wireless_dbg_manual)) {
            if (android.os.Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
                notifPerm.launch("android.permission.POST_NOTIFICATIONS")
            else openWireless()
        }
        Text(
            if (Pairing.port > 0) stringResource(R.string.pair_found, Pairing.port) else stringResource(R.string.pair_waiting),
            color = if (Pairing.port > 0) Pop.accent else Pop.sub, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    PopButton(stringResource(R.string.open_usb_dbg)) {
        val tries = listOf(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        var ok = false
        for (i in tries) { if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) { ok = true; break } }
        if (!ok) toast(ctx, ctx.getString(R.string.no_settings_page))
    }
    val num = KeyboardOptions(keyboardType = KeyboardType.Number)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Pop.text, unfocusedTextColor = Pop.text, focusedLabelColor = Pop.accent, unfocusedLabelColor = Pop.sub,
        focusedBorderColor = Pop.accent, unfocusedBorderColor = Pop.sub, cursorColor = Pop.accent,
    )
    OutlinedTextField(pairPort, { pairPort = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.pair_port)) }, singleLine = true, keyboardOptions = num, colors = fieldColors, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(code, { code = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.pair_code)) }, singleLine = true, keyboardOptions = num, colors = fieldColors, modifier = Modifier.fillMaxWidth())
    PopButton(stringResource(R.string.btn_pair), enabled = !busy && (pairPort.isNotBlank() || Pairing.port > 0) && code.isNotBlank()) {
        val pp = pairPort.toIntOrNull() ?: Pairing.port
        val ph = if (pairPort.isNotBlank()) "127.0.0.1" else (Pairing.host?.hostAddress ?: "127.0.0.1")
        run { Adb.pair(pp, code, ph).mapCatching { Adb.connectWireless(ctx, port.toIntOrNull()).getOrThrow() } }
    }
    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.conn_port)) }, singleLine = true, keyboardOptions = num, colors = fieldColors, modifier = Modifier.fillMaxWidth())
    PopButton(stringResource(R.string.btn_connect), enabled = !busy) { run { Adb.connectWireless(ctx, port.toIntOrNull()) } }
    PopButton(stringResource(R.string.btn_connect_5555), enabled = !busy) { run { Adb.connectLocal(port.toIntOrNull() ?: 5555) } }
}

/** Тихая попытка подключиться при запуске: без окон, если не получилось, подключение делается из меню. */
suspend fun autoConnect(ctx: android.content.Context) {
    Adb.awaitReady()
    if (Adb.isConnected) { Conn.refresh(); return }
    // root (Magisk) важнее ADB: компьютер и отладка по Wi-Fi не нужны (магнитолы, приставки)
    if (Cfg.useRoot && Adb.tryRoot()) { Conn.refresh(); return }
    val r = Adb.connectWireless(ctx, null)
    if (r.isFailure || !Adb.isConnected) Adb.connectLocal(5555)
    Conn.refresh()
}

/** Отдельное окно подключения ADB (из настроек). */
@Composable
fun AdbConnectDialog(onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    HDialog(onDismissRequest = onClose) {
        Column(
            Modifier.clip(RoundedCornerShape(24.dp)).background(Pop.card).padding(14.dp).fillMaxWidth()
                .heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { ConnectPanel(first, onDone = onClose, onBack = onClose) }
    }
}
