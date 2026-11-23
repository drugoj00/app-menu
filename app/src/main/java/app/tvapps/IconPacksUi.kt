@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.tvapps

import androidx.compose.ui.draw.drawBehind
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Один пример в предпросмотре набора: название приложения, родной значок и значок после применения набора. */
private class PrevItem(val label: String, val before: ImageBitmap?, val after: ImageBitmap?)

private class PrevState(val matched: Int, val total: Int, val items: List<PrevItem>, val rest: List<PrevItem>, val styled: Boolean)

/**
 * Окно «Наборы значков» (15.0): установленные наборы формата ADW/Nova/Apex, предпросмотр (родные значки и значки набора рядом), применение к меню
 * и к «Дому» и возврат к стандартным значкам. Свои картинки значков (выбор в приложении, из папки или ZIP) сильнее набора и остаются при смене набора.
 */
@Composable
fun IconPacksDialog(apps: List<Entry>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val packs = remember { IconPacks.installed(ctx) }
    var sel by remember { mutableStateOf(Cfg.iconPack.ifEmpty { packs.firstOrNull()?.first.orEmpty() }) }
    var prev by remember { mutableStateOf<PrevState?>(null) }
    val fr = remember { FocusRequester() }
    val style = Cfg.iconPackStyle
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    LaunchedEffect(sel, style) {
        prev = null
        if (sel.isEmpty()) return@LaunchedEffect
        prev = withContext(Dispatchers.Default) {
            val p = IconPacks.preview(ctx, sel, apps, 12)
            val data = IconPacks.load(ctx, sel)
            fun bmp(d: android.graphics.drawable.Drawable): ImageBitmap? = runCatching { d.toBitmap(96, 96).asImageBitmap() }.getOrNull()
            val items = p.samples.map { (e, d) -> PrevItem(e.label, bmp(e.icon), bmp(d)) }
            val have = p.samples.map { it.first.pkg }.toSet()
            // приложения, которых в наборе нет: как они выглядят с общим оформлением набора (фон и маска), если оно задано
            val rest = if (data.hasStyle) apps.filter { it.pkg !in have && IconPacks.fromPack(ctx, sel, it) == null }.take(6).mapNotNull { e ->
                IconPacks.styled(ctx, sel, e.icon, 96)?.let { PrevItem(e.label, bmp(e.icon), bmp(it)) }
            } else emptyList()
            PrevState(p.matched, p.total, items, rest, data.hasStyle)
        }
    }

    // окно такого же вида и размера, как остальные окна настроек (список, ввод): MotionDialog + FixedFont
    MotionDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MouseDragInDialog()
        val full = androidx.compose.ui.platform.LocalDensity.current
        val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
        FixedFont { androidx.compose.runtime.CompositionLocalProvider(LocalTh provides (LocalBaseTh.current ?: LocalTh.current)) {
        // предельная высота окна считается в плотности самого окна (FixedFont уменьшает dp на телефоне), иначе окно занимает лишь часть экрана
        val maxH = (screenH * full.density * 0.9f / androidx.compose.ui.platform.LocalDensity.current.density).dp
        Column(
            dlgWidth(340.dp, 640.dp).heightIn(max = maxH).clip(RoundedCornerShape(20.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.ip_title), color = t.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            // пояснение свёрнуто под названием (правило 24), как в остальных окнах
            var hintOpen by remember { mutableStateOf(false) }
            var hintFocus by remember { mutableStateOf(false) }
            Column {
                Row(
                    Modifier.onFocusChanged { hintFocus = it.isFocused }.clip(RoundedCornerShape(8.dp))
                        .border(if (hintFocus) 2.dp else 0.dp, if (hintFocus) t.accent else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) { hintOpen = !hintOpen }.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(stringResource(R.string.hint_more), color = t.sub, fontSize = 13.sp)
                    Text(if (hintOpen) "▴" else "ⓘ", color = t.accent, fontSize = 14.sp)
                }
                if (hintOpen) Text(stringResource(R.string.ip_hint), color = t.hint, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (packs.isEmpty()) Text(stringResource(R.string.iconpack_none), color = t.sub, fontSize = 14.sp)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // «Стандартные значки» всегда первой строкой: откат на родные значки приложений
                PackRow(stringResource(R.string.ip_native), "", Cfg.iconPack.isEmpty(), sel.isEmpty(), if (packs.isEmpty()) Modifier.focusRequester(fr) else Modifier) { sel = "" }
                packs.forEachIndexed { i, (pkg, label) ->
                    PackRow(label, "", Cfg.iconPack == pkg, sel == pkg, if (i == 0) Modifier.focusRequester(fr) else Modifier) { sel = pkg }
                }
                val pv = prev
                if (sel.isNotEmpty()) {
                    if (pv == null) Text(stringResource(R.string.working), color = t.accent, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    else {
                        Text(stringResource(R.string.ip_matched, pv.matched, pv.total), color = t.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                        if (pv.items.isEmpty()) Text(stringResource(R.string.ip_no_match), color = t.hint, fontSize = 12.sp)
                        else PrevGrid(stringResource(R.string.ip_prev_now), stringResource(R.string.ip_prev_will), pv.items)
                        if (pv.rest.isNotEmpty()) {
                            Text(stringResource(R.string.ip_prev_rest) + if (style) "" else " (" + stringResource(R.string.ip_rest_off) + ")", color = t.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                            PrevGrid(stringResource(R.string.ip_prev_now), stringResource(R.string.ip_prev_will), pv.rest)
                        }
                    }
                }
                // кнопки внутри прокручиваемой части: в узком окне телефона им нужно много места
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.ip_apply), true) {
                    if (sel == Cfg.iconPack) { Toast.makeText(ctx, ctx.getString(R.string.ip_already), Toast.LENGTH_SHORT).show(); return@Chip }
                    Cfg.changeIconPack(sel)
                    Toast.makeText(ctx, ctx.getString(if (sel.isEmpty()) R.string.ip_reset_done else R.string.ip_applied), Toast.LENGTH_LONG).show()
                }
                if (Cfg.iconPack.isNotEmpty()) Chip(stringResource(R.string.ip_reset), false) {
                    Cfg.changeIconPack(""); sel = ""
                    Toast.makeText(ctx, ctx.getString(R.string.ip_reset_done), Toast.LENGTH_LONG).show()
                }
                if (prev?.styled == true) Chip(stringResource(R.string.ip_style), Cfg.iconPackStyle) { Cfg.changeIconPackStyle(!Cfg.iconPackStyle) }
                Chip(stringResource(R.string.close), false) { onClose() }
                }
            }
        } } }
    }
}

@Composable
private fun PackRow(title: String, note: String, applied: Boolean, selected: Boolean, extra: Modifier, onClick: () -> Unit) {
    val t = LocalTh.current
    var focused by remember { mutableStateOf(false) }
    val (hm, hover) = rememberHover()
    Row(
        Modifier.fillMaxWidth().then(extra).then(hm).onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .drawBehind { drawRect(if (focused || hover) t.tileFocus else if (selected) t.chipSel else t.tile) }
            .focusBorder({ focused }, 3.dp, 0.dp, t.accent, Color.Transparent, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = t.text, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (note.isNotEmpty()) Text(note, color = t.accent, fontSize = 12.sp, maxLines = 1)
        if (applied) Text("✓", color = t.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** Примеры значков: сверху родные, снизу значки набора (подписи рядов слева). */
@Composable
private fun PrevGrid(nowLabel: String, willLabel: String, items: List<PrevItem>) {
    val t = LocalTh.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(nowLabel, color = t.hint, fontSize = 11.sp, modifier = Modifier.size(width = 62.dp, height = 50.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items.forEach { Tile(it.before) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(willLabel, color = t.accent, fontSize = 11.sp, modifier = Modifier.size(width = 62.dp, height = 50.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items.forEach { Tile(it.after) } }
        }
    }
}

@Composable
private fun Tile(b: ImageBitmap?) {
    val t = LocalTh.current
    Column(Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)).background(t.tile), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (b != null) Image(b, null, Modifier.size(44.dp))
    }
}
