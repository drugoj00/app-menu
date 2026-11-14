package app.tvapps

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay

/** Состояние панели «Все приложения» на «Доме»: запрос из поиска верхней строки и просьба перейти в панель. */
internal object AllPanel {
    /** Что искали в верхней строке «Дома»: панель показывает только подходящие приложения; пусто — все. */
    var query by mutableStateOf("")
    /** Растёт при каждой просьбе «перейти в панель» (поиск в верхней строке): прокрутка к панели и курсор на первую плитку. */
    val goTick = mutableIntStateOf(0)
    /** Курсор сейчас в панели (нужно кнопке «Назад»: сначала сбросить поиск, затем вернуться наверх). */
    var focusInside by mutableStateOf(false)

    fun search(q: String) { query = q.trim(); goTick.intValue++ }
}

private fun glassAlpha(): Float = when (Cfg.homeAllGlass) { "light" -> 0.30f; "dense" -> 0.82f; else -> 0.55f }

/**
 * Панель «Все приложения»: стеклянный фон, заголовок и прокручиваемая сетка всех приложений (порядок и скрытие как в меню).
 * Высота задаётся снаружи (видимая область «Дома»): сетка прокручивается внутри, пультом курсор свободно уходит вверх (к виджетам) и вниз.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AllAppsPanel(apps: List<Entry>, height: Dp, openApp: (Entry) -> Unit, onDlg: (Dlg?) -> Unit) {
    val t = LocalTh.current
    @Suppress("UNUSED_VARIABLE") val dep = Cfg.launchTick
    val shown = remember(apps, AllPanel.query, Cfg.sort, Cfg.order, Cfg.showHidden, Cfg.hidden, Cfg.launchTick) {
        arrange(apps, AllPanel.query).filter { !it.isFolder }
    }
    val grid = rememberLazyGridState()
    val bring = remember { BringIntoViewRequester() }
    val firstReq = remember { FocusRequester() }
    // «перейти в панель» (поиск в верхней строке): панель в видимую область, сетка в начало, курсор на первую плитку
    LaunchedEffect(AllPanel.goTick.intValue) {
        if (AllPanel.goTick.intValue == 0) return@LaunchedEffect
        runCatching { bring.bringIntoView() }
        delay(200)
        runCatching { grid.scrollToItem(0) }
        delay(80)
        runCatching { firstReq.requestFocus() }
    }
    val shape = RoundedCornerShape(20.dp)
    val glass = if (t.dark) Color(0xFF0B1220).copy(alpha = glassAlpha()) else Color.White.copy(alpha = glassAlpha())
    Column(
        Modifier.fillMaxWidth().height(height).bringIntoViewRequester(bring).clip(shape).background(glass, shape)
            .border(1.dp, t.text.copy(alpha = 0.14f), shape).padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                stringResource(R.string.home_all_btn).uppercase(), color = t.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            Text(shown.size.toString(), color = t.sub, fontSize = 14.sp)
            if (AllPanel.query.isNotEmpty()) {
                HomeBox(
                    shape = RoundedCornerShape(50), bg = t.tile.copy(alpha = 0.85f), onClick = { AllPanel.query = "" }, onLong = { AllPanel.query = "" },
                    memKey = "allp|clear",
                ) {
                    Text(
                        stringResource(R.string.all_panel_filter, AllPanel.query) + "  ✕", color = t.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.all_panel_empty), color = t.sub, fontSize = 16.sp)
            }
        } else {
            // размер плиток как в меню: значок и столбцы берутся из настроек программы
            val icon = ((when (Cfg.iconSize) { 3 -> 34f; 0 -> 52f; 2 -> 96f; else -> 72f }) * Cfg.iconScale / 100f).coerceAtLeast(30f)
            val cellW = (icon + 34f).coerceAtLeast(76f)
            LazyVerticalGrid(
                columns = if (Cfg.columns > 0) GridCells.Fixed(Cfg.columns) else GridCells.Adaptive(cellW.dp),
                state = grid,
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScrollbar(grid),
                contentPadding = PaddingValues(end = 14.dp, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown, key = { it.pkg }) { e ->
                    PanelTile(e, icon, if (e === shown.first()) firstReq else null, openApp, onDlg)
                }
            }
        }
    }
}

@Composable
private fun PanelTile(e: Entry, icon: Float, req: FocusRequester?, openApp: (Entry) -> Unit, onDlg: (Dlg?) -> Unit) {
    val t = LocalTh.current
    val bmp = remember(e.pkg, e.icon, e.bmp) { e.bmp ?: e.icon.toBitmap(128, 128).asImageBitmap() }
    HomeBox(
        Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), if (t.dark) Color.Black.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.55f),
        focusReq = req, onClick = { openApp(e) }, onLong = { onDlg(Dlg.AppActions(e.pkg)) }, memKey = "allp|" + e.pkg,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IconPlate(icon.dp, (icon * 0.75f).dp, icon.dp, bmp)
            if (Cfg.showLabels) {
                Text(
                    e.label, color = t.text, fontSize = (icon / 5.6f).coerceIn(11f, 15f).sp, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}
