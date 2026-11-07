package app.tvapps

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun onOff(b: Boolean, on: String, off: String) = if (b) on else off

/** Образец цвета для окон: круг с тонким тёмным ободком (белый и светлые цвета видны и на светлом окне). */
private fun colorDot(argb: Int): androidx.compose.ui.graphics.ImageBitmap {
    val sz = 64
    val bm = android.graphics.Bitmap.createBitmap(sz, sz, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bm)
    val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    pt.style = android.graphics.Paint.Style.FILL; pt.color = argb
    c.drawCircle(sz / 2f, sz / 2f, sz / 2f - 4f, pt)
    pt.style = android.graphics.Paint.Style.STROKE; pt.strokeWidth = 4f; pt.color = 0xFF64748B.toInt()
    c.drawCircle(sz / 2f, sz / 2f, sz / 2f - 4f, pt)
    return bm.asImageBitmap()
}

/** Цвета обводки плитки: 0 — цвет программы (акцент темы), дальше готовые цвета. */
private val BORDER_COLORS = listOf(0L, 0xFFFFFFFFL, 0xFF000000L, 0xFFEF4444L, 0xFFF97316L, 0xFFFACC15L, 0xFF22C55EL, 0xFF06B6D4L, 0xFF3B82F6L, 0xFFA855F7L, 0xFFEC4899L)
private val BORDER_NAMES = listOf(
    R.string.bd_c0, R.string.bd_c1, R.string.bd_c2, R.string.bd_c3, R.string.bd_c4, R.string.bd_c5,
    R.string.bd_c6, R.string.bd_c7, R.string.bd_c8, R.string.bd_c9, R.string.bd_c10,
)

/** Все окна настроек «Дома»: открывается нужное по [dlg]. Изменения сразу пишутся в [HomeStore]. */
@Composable
internal fun HomeDialogs(
    dlg: Dlg?, apps: List<Entry>, setDlg: (Dlg?) -> Unit,
    startMove: (String) -> Unit, startMoveStatus: (String) -> Unit, pickImage: (String) -> Unit, refreshWeather: () -> Unit,
    addAppWidget: (String, Int, android.appwidget.AppWidgetProviderInfo) -> Unit, disableHome: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // «Оформление»: менять только этот значок или все значки строки
    var lookAll by remember { mutableStateOf(false) }
    var wgAll by remember { mutableStateOf(false) }
    val close = { setDlg(null) }
    // «Настройки программы»: открывают настройки меню (тема, резервная копия, ADB, переключатели модулей); работают и при выключенном меню
    val openAppSettings = {
        close()
        ctx.startActivity(android.content.Intent(ctx, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("from_home", true).putExtra("screen", "settings"))
    }
    val on = stringResource(R.string.on)
    val off = stringResource(R.string.off)
    when (dlg) {
        null -> {}

        is Dlg.ItemD -> {
            val row = HomeStore.row(dlg.rowId)
            val item = row?.items?.firstOrNull { it.id == dlg.itemId }
            val auto = item == null
            val ref = item?.ref ?: dlg.itemId.removePrefix("a:")
            val entry = apps.firstOrNull { it.pkg == ref }
            val name = item?.label?.ifBlank { null } ?: entry?.label ?: when (item?.kind) {
                "menu" -> stringResource(R.string.home_all_btn); "settings" -> stringResource(R.string.settings); else -> ref
            }
            val opts = buildList {
                add(Opt(stringResource(R.string.d_program_settings)) { openAppSettings() })      // настройки программы — первой строкой
                add(Opt(stringResource(R.string.d_screen)) { setDlg(Dlg.Screen) })      // настройки «Дома» — второй
                if (!auto) {
                    add(Opt(stringResource(R.string.d_move)) { close(); startMove(dlg.itemId) })
                    add(Opt(stringResource(R.string.d_to_row)) { setDlg(Dlg.MoveToRow(dlg.itemId)) })
                    add(Opt(stringResource(R.string.d_look)) { setDlg(Dlg.Look(dlg.rowId, dlg.itemId)) })
                }
                if (entry != null) add(Opt(stringResource(if (Cfg.isFavorite(entry.pkg)) R.string.d_unfav else R.string.d_fav)) { Cfg.toggleFavorite(entry.pkg); close() })
                if (entry != null) add(Opt(stringResource(R.string.d_actions)) { setDlg(Dlg.AppActions(entry.pkg)) })
                if (entry != null) add(Opt(stringResource(R.string.d_info)) {
                    close()
                    runCatching {
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + entry.pkg)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.no_settings_page), Toast.LENGTH_SHORT).show() }
                })
                if (!auto) add(Opt(stringResource(R.string.d_remove), danger = true) { HomeStore.removeItem(dlg.rowId, dlg.itemId); close() })
                add(Opt(stringResource(R.string.d_add_here)) { setDlg(Dlg.AddItem(dlg.rowId)) })
                add(Opt(stringResource(R.string.row_add_below)) { setDlg(Dlg.AddRow(dlg.rowId)) })
                add(Opt(stringResource(R.string.d_row_settings)) { setDlg(Dlg.RowD(dlg.rowId)) })
                add(Opt(stringResource(R.string.d_android)) { close(); openSys(ctx, android.provider.Settings.ACTION_SETTINGS) })
            }
            ListDialog(name, opts, close)
        }

        is Dlg.Look -> {
            val row = HomeStore.row(dlg.rowId)
            val item = row?.items?.firstOrNull { it.id == dlg.itemId }
            if (item == null) { close(); return }
            val set = { f: (HItem) -> HItem ->
                if (lookAll) HomeStore.updateRow(dlg.rowId) { r -> r.copy(items = r.items.map(f)) } else HomeStore.updateItem(dlg.rowId, dlg.itemId, f)
            }
            // режим «все значки строки»: оформление всех значков сразу выравнивается по значку, на котором открыто окно (чтобы подписи не врали)
            LaunchedEffect(dlg.itemId, lookAll) {
                if (lookAll) HomeStore.updateRow(dlg.rowId) { r ->
                    r.copy(items = r.items.map { it.copy(radius = item.radius, bg = item.bg, shadow = item.shadow, borderW = item.borderW, borderC = item.borderC, borderHover = item.borderHover) })
                }
            }
            val accent = LocalTh.current.accent
            val ci = BORDER_COLORS.indexOf(item.borderC).coerceAtLeast(0)
            val swatch = remember(ci, accent) {
                colorDot(if (ci == 0) accent.toArgb() else BORDER_COLORS[ci].toInt())
            }
            val opts = listOf(
                Opt(stringResource(R.string.look_scope), if (lookAll) stringResource(R.string.look_scope_all) else stringResource(R.string.look_scope_one), onClick = { lookAll = !lookAll }),
                Opt(stringResource(R.string.look_view), if (item.mode == "banner") stringResource(R.string.look_banner) else stringResource(R.string.look_icon), onClick = {
                    val m = if (item.mode == "banner") "icon" else "banner"
                    set { it.copy(mode = m) }
                }),
                Opt(stringResource(R.string.look_img), onOff(item.img, stringResource(R.string.look_img_set), stringResource(R.string.look_img_none)), onClick = { pickImage(item.id) }),
                Opt(stringResource(R.string.bn_make), onClick = { setDlg(Dlg.BannerStyle(dlg.rowId, dlg.itemId)) }),
                Opt(stringResource(R.string.look_img_clear), onClick = { HomeImg.delete(item.id); set { it.copy(img = false) } }),
                Opt(stringResource(R.string.look_radius), "${item.radius}", { val v = (item.radius - 1).coerceAtLeast(0); set { it.copy(radius = v) } }, { val v = (item.radius + 1).coerceAtMost(48); set { it.copy(radius = v) } }),
                Opt(
                    stringResource(R.string.look_bg),
                    when (item.bg) { 1 -> stringResource(R.string.look_bg_clear); 2 -> stringResource(R.string.look_bg_card); else -> stringResource(R.string.look_bg_auto) },
                    onClick = { val v = (item.bg + 1) % 3; set { it.copy(bg = v) } },
                ),
                Opt(stringResource(R.string.look_shadow), onOff(item.shadow, on, off), onClick = { val v = !item.shadow; set { it.copy(shadow = v) } }),
                Opt(
                    stringResource(R.string.bd_width), if (item.borderW == 0) off else "${item.borderW}",
                    { val v = (item.borderW - 1).coerceAtLeast(0); set { it.copy(borderW = v) } }, { val v = (item.borderW + 1).coerceAtMost(12); set { it.copy(borderW = v) } },
                ),
                Opt(stringResource(R.string.bd_when), onOff(item.borderHover, stringResource(R.string.bd_hover), stringResource(R.string.bd_always)), onClick = { val v = !item.borderHover; set { it.copy(borderHover = v) } }),
                Opt(
                    stringResource(R.string.bd_color), stringResource(BORDER_NAMES[ci]),
                    { val v = BORDER_COLORS[(ci + BORDER_COLORS.size - 1) % BORDER_COLORS.size]; set { it.copy(borderC = v) } },
                    { val v = BORDER_COLORS[(ci + 1) % BORDER_COLORS.size]; set { it.copy(borderC = v) } },
                    icon = swatch,
                ),
                Opt(stringResource(R.string.look_name), item.label, onClick = { setDlg(Dlg.Label(dlg.rowId, dlg.itemId)) }),
            )
            ListDialog(stringResource(R.string.look_title), opts, { setDlg(Dlg.ItemD(dlg.rowId, dlg.itemId)) })
        }

        is Dlg.Label -> {
            val item = HomeStore.row(dlg.rowId)?.items?.firstOrNull { it.id == dlg.itemId }
            TextDialog(stringResource(R.string.look_name), item?.label.orEmpty(), stringResource(R.string.look_name_hint), { v ->
                HomeStore.updateItem(dlg.rowId, dlg.itemId) { it.copy(label = v) }
                setDlg(Dlg.Look(dlg.rowId, dlg.itemId))
            }, { setDlg(Dlg.Look(dlg.rowId, dlg.itemId)) })
        }

        is Dlg.RowD -> {
            val row = HomeStore.row(dlg.rowId)
            if (row == null) { close(); return }
            var sure by remember(dlg.rowId) { mutableStateOf(false) }
            val upd = { f: (HRow) -> HRow -> HomeStore.updateRow(row.id, f) }
            val autoNames = listOf("" to stringResource(R.string.auto_none), "fav" to stringResource(R.string.auto_fav), "recent" to stringResource(R.string.auto_recent), "hdmi" to stringResource(R.string.auto_hdmi), "new" to stringResource(R.string.auto_new))
            val opts = buildList {
                add(Opt(stringResource(R.string.d_program_settings)) { openAppSettings() })      // настройки программы — первой строкой
                add(Opt(stringResource(R.string.d_screen)) { setDlg(Dlg.Screen) })      // настройки «Дома» — второй
                add(Opt(stringResource(R.string.row_title), row.title, onClick = { setDlg(Dlg.Rename(row.id)) }))
                add(Opt(stringResource(R.string.row_title_visible), onOff(row.showTitle, on, off), onClick = { upd { it.copy(showTitle = !it.showTitle) } }))
                when (row.kind) {
                    "apps" -> {
                        add(Opt(stringResource(R.string.row_size_icons), "${row.size}", { upd { it.copy(size = (it.size - 1).coerceAtLeast(32)) } }, { upd { it.copy(size = (it.size + 1).coerceAtMost(180)) } }))
                        add(Opt(stringResource(R.string.row_fit), onOff(row.fit, on, off), onClick = { upd { it.copy(fit = !it.fit) } }))
                        if (row.fit) add(Opt(stringResource(R.string.row_perline), if (row.perLine == 0) stringResource(R.string.perline_auto) else "${row.perLine}", { upd { it.copy(perLine = (it.perLine - 1).coerceAtLeast(0)) } }, { upd { it.copy(perLine = (it.perLine + 1).coerceAtMost(40)) } }))
                        add(Opt(stringResource(R.string.row_wrap), onOff(row.wrap, on, off), onClick = { upd { it.copy(wrap = !it.wrap) } }))
                        add(Opt(stringResource(R.string.row_itemmode), if (row.itemMode == "banner") stringResource(R.string.look_banner) else stringResource(R.string.look_icon), onClick = {
                            val m = if (row.itemMode == "banner") "icon" else "banner"
                            upd { r -> r.copy(itemMode = m, items = r.items.map { i -> i.copy(mode = m, showLabel = m == "icon") }) }
                        }))
                        add(Opt(stringResource(R.string.row_labels), onOff(row.labels, on, off), onClick = { upd { it.copy(labels = !it.labels) } }))
                        add(Opt(stringResource(R.string.row_add_item), onClick = { setDlg(Dlg.AddItem(row.id)) }))
                        add(Opt(stringResource(R.string.row_auto), autoNames.firstOrNull { it.first == row.auto }?.second.orEmpty(), onClick = {
                            val i = autoNames.indexOfFirst { it.first == row.auto }
                            upd { it.copy(auto = autoNames[(i + 1) % autoNames.size].first) }
                        }))
                        if (row.auto.isNotEmpty()) add(Opt(stringResource(R.string.row_count), "${row.count}", { upd { it.copy(count = (it.count - 1).coerceAtLeast(1)) } }, { upd { it.copy(count = (it.count + 1).coerceAtMost(40)) } }))
                    }
                    "cards" -> {
                        add(Opt(stringResource(R.string.row_size_cards), "${row.size}", { upd { it.copy(size = (it.size - 1).coerceAtLeast(50)) } }, { upd { it.copy(size = (it.size + 1).coerceAtMost(260)) } }))
                        add(Opt(stringResource(R.string.row_fit), onOff(row.fit, on, off), onClick = { upd { it.copy(fit = !it.fit) } }))
                        if (row.fit) add(Opt(stringResource(R.string.row_perline), if (row.perLine == 0) stringResource(R.string.perline_auto) else "${row.perLine}", { upd { it.copy(perLine = (it.perLine - 1).coerceAtLeast(0)) } }, { upd { it.copy(perLine = (it.perLine + 1).coerceAtMost(40)) } }))
                        add(Opt(stringResource(R.string.row_source), onClick = { setDlg(Dlg.CardSource(row.id)) }, value = sourceName(ctx, row.auto)))
                        if (row.auto.startsWith("cat:")) add(Opt(stringResource(R.string.cq_menu), row.auto.substringAfter('|', ""), onClick = { setDlg(Dlg.CatQuery(row.id, row.auto.removePrefix("cat:").substringBefore('|'))) }))
                        add(Opt(stringResource(R.string.row_count), "${row.count}", { upd { it.copy(count = (it.count - 1).coerceAtLeast(1)) } }, { upd { it.copy(count = (it.count + 1).coerceAtMost(60)) } }))
                        add(Opt(stringResource(R.string.row_aspect), row.aspect, onClick = {
                            val l = listOf("16:9", "1:1", "2:3")
                            upd { it.copy(aspect = l[(l.indexOf(it.aspect) + 1) % l.size]) }
                        }))
                    }
                    else -> {
                        add(Opt(stringResource(R.string.row_size_widgets), "${row.size}", { upd { it.copy(size = (it.size - 1).coerceAtLeast(60)) } }, { upd { it.copy(size = (it.size + 1).coerceAtMost(320)) } }))
                        add(Opt(stringResource(R.string.row_slots), "${row.slots.size}", { HomeStore.setSlotCount(row.id, row.slots.size - 1) }, { HomeStore.setSlotCount(row.id, row.slots.size + 1) }))
                        // оформление виджетов прямо в настройках строки (всем виджетам строки сразу); по одному: долгое нажатие на виджет
                        val w0 = row.slots.firstOrNull()
                        if (w0 != null) {
                            val allW = { f: (HWidget) -> HWidget -> upd { r -> r.copy(slots = r.slots.map(f)) } }
                            val rci = BORDER_COLORS.indexOf(w0.borderC).coerceAtLeast(0)
                            val rAccent = LocalTh.current.accent
                            val rsw = remember(rci, rAccent) {
                                colorDot(if (rci == 0) rAccent.toArgb() else BORDER_COLORS[rci].toInt())
                            }
                            add(Opt(stringResource(R.string.look_radius), "${w0.radius}", { val v = (w0.radius - 1).coerceAtLeast(0); allW { it.copy(radius = v) } }, { val v = (w0.radius + 1).coerceAtMost(60); allW { it.copy(radius = v) } }))
                            add(Opt(stringResource(R.string.bd_width), if (w0.borderW == 0) off else "${w0.borderW}", { val v = (w0.borderW - 1).coerceAtLeast(0); allW { it.copy(borderW = v) } }, { val v = (w0.borderW + 1).coerceAtMost(12); allW { it.copy(borderW = v) } }))
                            add(Opt(stringResource(R.string.bd_when), onOff(w0.borderHover, stringResource(R.string.bd_hover), stringResource(R.string.bd_always)), onClick = { val v = !w0.borderHover; allW { it.copy(borderHover = v) } }))
                            add(Opt(stringResource(R.string.bd_color), stringResource(BORDER_NAMES[rci]),
                                { val v = BORDER_COLORS[(rci + BORDER_COLORS.size - 1) % BORDER_COLORS.size]; allW { it.copy(borderC = v) } },
                                { val v = BORDER_COLORS[(rci + 1) % BORDER_COLORS.size]; allW { it.copy(borderC = v) } }, icon = rsw))
                        }
                    }
                }
                add(Opt(stringResource(R.string.screen_row_gap), "${HomeStore.layout.rowGap}", { HomeStore.set(HomeStore.layout.copy(rowGap = (HomeStore.layout.rowGap - 1).coerceAtLeast(0))) }, { HomeStore.set(HomeStore.layout.copy(rowGap = (HomeStore.layout.rowGap + 1).coerceAtMost(60))) }))
                add(Opt(stringResource(R.string.row_gap), "${row.gap}", { upd { it.copy(gap = (it.gap - 1).coerceAtLeast(0)) } }, { upd { it.copy(gap = (it.gap + 1).coerceAtMost(60)) } }))
                add(Opt(stringResource(R.string.row_up)) { HomeStore.moveRow(row.id, -1) })
                add(Opt(stringResource(R.string.row_down)) { HomeStore.moveRow(row.id, 1) })
                add(Opt(stringResource(R.string.row_add_below)) { setDlg(Dlg.AddRow(row.id)) })
                add(Opt(if (sure) stringResource(R.string.row_delete_sure) else stringResource(R.string.row_delete), danger = true) {
                    if (sure) { HomeStore.removeRow(row.id); close() } else sure = true
                })
            }
            ListDialog(row.title.ifBlank { stringResource(R.string.row_noname) }, opts, close)
        }

        is Dlg.AddRow -> {
            fun make(kind: String) {
                val id = HomeStore.newId("r")
                val r = when (kind) {
                    "cards" -> HRow(id, "", true, "cards", size = 100, count = 20)
                    "widgets" -> HRow(id, "", true, "widgets", size = 120, slots = listOf(HWidget("weather")))
                    "hdmi" -> HRow(id, ctx.getString(R.string.home_inputs), true, "apps", emptyList(), "hdmi", 8, 72)
                    else -> HRow(id, "", true, "apps", size = 72)
                }
                HomeStore.addRow(r, dlg.afterId)
                setDlg(when (kind) { "cards" -> Dlg.CardSource(id); "widgets", "hdmi" -> Dlg.RowD(id); else -> Dlg.PickApps(id) })
            }
            ListDialog(
                stringResource(R.string.add_what),
                listOf(
                    Opt(stringResource(R.string.add_icons)) { make("apps") },
                    Opt(stringResource(R.string.add_cards)) { make("cards") },
                    Opt(stringResource(R.string.add_widgets)) { make("widgets") },
                    Opt(stringResource(R.string.add_hdmi_row)) { make("hdmi") },
                ),
                close,
            )
        }

        is Dlg.AddItem -> ListDialog(
            stringResource(R.string.add_item_title),
            listOf(
                Opt(stringResource(R.string.add_app)) { setDlg(Dlg.PickApps(dlg.rowId)) },
                Opt(stringResource(R.string.add_hdmi)) { setDlg(Dlg.PickHdmi(dlg.rowId)) },
                Opt(stringResource(R.string.home_all_btn)) { HomeStore.addItem(dlg.rowId, HItem(HomeStore.newId("i"), "menu")); close() },
                Opt(stringResource(R.string.settings)) { HomeStore.addItem(dlg.rowId, HItem(HomeStore.newId("i"), "settings")); close() },
            ),
            close,
        )

        is Dlg.PickApps -> {
            val row = HomeStore.row(dlg.rowId)
            val have = row?.items?.filter { it.kind == "app" }?.associateBy { it.ref }.orEmpty()
            val opts = apps.sortedBy { it.label.lowercase() }.map { e ->
                Opt(e.label, if (e.pkg in have) "✓" else "", icon = e.bmp ?: runCatching { e.icon.toBitmap(64, 64).asImageBitmap() }.getOrNull(), onClick = {
                    val h = have[e.pkg]
                    if (h != null) HomeStore.removeItem(dlg.rowId, h.id)
                    else HomeStore.addItem(dlg.rowId, HItem(HomeStore.newId("i"), "app", e.pkg, mode = row?.itemMode ?: "banner", showLabel = row?.itemMode == "icon"))
                })
            }
            ListDialog(stringResource(R.string.pick_apps), opts, close)
        }

        is Dlg.PickHdmi -> {
            val inputs by produceState(emptyList<TvIn>()) { value = withContext(Dispatchers.IO) { TvInputs.list(ctx) } }
            val row = HomeStore.row(dlg.rowId)
            val have = row?.items?.filter { it.kind == "hdmi" }?.associateBy { it.ref }.orEmpty()
            val opts = if (inputs.isEmpty()) listOf(Opt(stringResource(R.string.pick_none_hdmi)) { close() })
            else inputs.map { i ->
                Opt(i.label, if (i.id in have) "✓" else "", onClick = {
                    val h = have[i.id]
                    if (h != null) HomeStore.removeItem(dlg.rowId, h.id)
                    else HomeStore.addItem(dlg.rowId, HItem(HomeStore.newId("i"), "hdmi", i.id, i.label, mode = row?.itemMode ?: "banner", showLabel = row?.itemMode == "icon"))
                })
            }
            ListDialog(stringResource(R.string.pick_hdmi), opts, close)
        }

        is Dlg.CardSource -> {
            val channels by produceState<List<ChRow>?>(null) { value = withContext(Dispatchers.IO) { Channels.load(ctx) } }
            val ch = channels
            val label = { pkg: String -> runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
            val catApps by produceState(emptyList<Pair<String, String>>()) { value = withContext(Dispatchers.IO) { Catalogs.providerApps(ctx) } }
            fun pick(auto: String, title: String) {
                HomeStore.updateRow(dlg.rowId) { it.copy(auto = auto, title = it.title.ifBlank { title }) }
                setDlg(Dlg.RowD(dlg.rowId))
            }
            val opts = buildList {
                // свои списки: избранное (выбранные вручную) и что смотрел; в них карточки попадают из любых программ
                add(Opt(stringResource(R.string.mine_src_fav)) { pick("mine:fav", ctx.getString(R.string.mine_src_fav)) })
                add(Opt(stringResource(R.string.mine_src_seen)) { pick("mine:seen", ctx.getString(R.string.mine_src_seen)) })
                if (ch == null) add(Opt(stringResource(R.string.working)) {})
                else {
                    if (!Channels.granted(ctx)) add(Opt(stringResource(R.string.home_channels_on)) {
                        Toast.makeText(ctx, ctx.getString(R.string.home_channels_hint), Toast.LENGTH_LONG).show()
                    })
                    if (ch.any { it.key == "watch_next" }) add(Opt(stringResource(R.string.home_watch_next)) { pick("watch_next", ctx.getString(R.string.home_watch_next)) })
                    val real = ch.filter { it.key != "watch_next" }
                    for (pkg in real.map { it.pkg }.distinct()) add(Opt(stringResource(R.string.src_app, label(pkg))) { pick("pkg:$pkg", label(pkg)) })
                    for (c in real) add(Opt(label(c.pkg) + " · " + c.title, "${c.items.size}") { pick("ch:" + c.key, c.title.ifBlank { label(c.pkg) }) })
                    // карточки из каталога любой программы с поиском (не только тех, что публикуют каналы)
                    for ((pkg, name) in catApps) add(Opt(stringResource(R.string.src_catalog, name)) { setDlg(Dlg.CatQuery(dlg.rowId, pkg)) })
                    if (isEmpty()) add(Opt(stringResource(R.string.src_none)) { close() })
                }
            }
            ListDialog(stringResource(R.string.src_title), opts, { setDlg(Dlg.RowD(dlg.rowId)) })
        }

        is Dlg.CatQuery -> {
            val row = HomeStore.row(dlg.rowId)
            val name = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(dlg.pkg, 0)).toString() }.getOrDefault(dlg.pkg)
            val cur = row?.auto?.takeIf { it.startsWith("cat:" + dlg.pkg + "|") }?.substringAfter('|').orEmpty()
            TextDialog(stringResource(R.string.cq_title, name), cur, stringResource(R.string.cq_hint), { v ->
                if (v.isNotBlank()) {
                    HomeStore.updateRow(dlg.rowId) { it.copy(auto = "cat:" + dlg.pkg + "|" + v.trim(), title = it.title.ifBlank { name }) }
                    setDlg(Dlg.RowD(dlg.rowId))
                }
            }, { setDlg(Dlg.CardSource(dlg.rowId)) })
        }

        is Dlg.Rename -> {
            val row = HomeStore.row(dlg.rowId)
            TextDialog(stringResource(R.string.row_title), row?.title.orEmpty(), stringResource(R.string.rename_hint), { v ->
                HomeStore.updateRow(dlg.rowId) { it.copy(title = v) }
                setDlg(Dlg.RowD(dlg.rowId))
            }, { setDlg(Dlg.RowD(dlg.rowId)) })
        }

        is Dlg.WidgetD -> {
            val row = HomeStore.row(dlg.rowId)
            val w = row?.slots?.getOrNull(dlg.slot)
            if (row == null || w == null) { close(); return }
            val upd = { f: (HWidget) -> HWidget -> HomeStore.updateSlot(row.id, dlg.slot, f) }
            // «Менять: только этот виджет / все виджеты строки»: новое значение считается один раз и ставится всем одинаковое
            var sureWgDel by remember(dlg.rowId, dlg.slot) { mutableStateOf(false) }
            val updLook = { f: (HWidget) -> HWidget -> if (wgAll) HomeStore.updateRow(row.id) { r -> r.copy(slots = r.slots.map(f)) } else upd(f) }
            LaunchedEffect(dlg.rowId, dlg.slot, wgAll) {
                if (wgAll) HomeStore.updateRow(row.id) { r -> r.copy(slots = r.slots.map { it.copy(radius = w.radius, borderW = w.borderW, borderC = w.borderC, borderHover = w.borderHover) }) }
            }
            val wci = BORDER_COLORS.indexOf(w.borderC).coerceAtLeast(0)
            val wAccent = LocalTh.current.accent
            val wsw = remember(wci, wAccent) {
                colorDot(if (wci == 0) wAccent.toArgb() else BORDER_COLORS[wci].toInt())
            }
            val opts = buildList {
                add(Opt(stringResource(R.string.d_program_settings)) { openAppSettings() })      // настройки программы — первой строкой
                add(Opt(stringResource(R.string.d_screen)) { setDlg(Dlg.Screen) })      // настройки «Дома» — второй
                add(Opt(stringResource(R.string.wg_type), widgetName(w.type), onClick = { setDlg(Dlg.WidgetType(row.id, dlg.slot)) }))
                when (w.type) {
                    "weather" -> {
                        add(Opt(stringResource(R.string.wg_city), w.city.ifBlank { stringResource(R.string.wg_city_auto) }, onClick = { setDlg(Dlg.WidgetCity(row.id, dlg.slot)) }))
                        add(Opt(stringResource(R.string.wg_days), "${w.n}", { upd { it.copy(n = (it.n - 1).coerceAtLeast(0)) } }, { upd { it.copy(n = (it.n + 1).coerceAtMost(5)) } }))
                        add(Opt(stringResource(R.string.wg_refresh)) { refreshWeather(); close() })
                    }
                    "clock" -> {
                        add(Opt(stringResource(R.string.wg_time_fmt), if ((w.n and 1) != 0) "12 h" else "24 h", onClick = { upd { it.copy(n = it.n xor 1) } }))
                        add(Opt(stringResource(R.string.wg_seconds), onOff((w.n and 2) != 0, on, off), onClick = { upd { it.copy(n = it.n xor 2) } }))
                        add(Opt(stringResource(R.string.wg_show_date), onOff(w.flag, on, off), onClick = { upd { it.copy(flag = !it.flag) } }))
                    }
                }
                if (row.slots.size > 1) add(Opt(stringResource(R.string.wgl_scope), if (wgAll) stringResource(R.string.wgl_all) else stringResource(R.string.wgl_one), onClick = { wgAll = !wgAll }))
                add(Opt(stringResource(R.string.look_radius), "${w.radius}", { val v = (w.radius - 1).coerceAtLeast(0); updLook { it.copy(radius = v) } }, { val v = (w.radius + 1).coerceAtMost(60); updLook { it.copy(radius = v) } }))
                add(Opt(stringResource(R.string.bd_width), if (w.borderW == 0) off else "${w.borderW}", { val v = (w.borderW - 1).coerceAtLeast(0); updLook { it.copy(borderW = v) } }, { val v = (w.borderW + 1).coerceAtMost(12); updLook { it.copy(borderW = v) } }))
                add(Opt(stringResource(R.string.bd_when), onOff(w.borderHover, stringResource(R.string.bd_hover), stringResource(R.string.bd_always)), onClick = { val v = !w.borderHover; updLook { it.copy(borderHover = v) } }))
                add(Opt(stringResource(R.string.bd_color), stringResource(BORDER_NAMES[wci]),
                    { val v = BORDER_COLORS[(wci + BORDER_COLORS.size - 1) % BORDER_COLORS.size]; updLook { it.copy(borderC = v) } },
                    { val v = BORDER_COLORS[(wci + 1) % BORDER_COLORS.size]; updLook { it.copy(borderC = v) } }, icon = wsw))
                add(Opt(stringResource(R.string.wg_font), "${w.fs}%", { upd { it.copy(fs = (it.fs - 1).coerceAtLeast(50)) } }, { upd { it.copy(fs = (it.fs + 1).coerceAtMost(250)) } }))
                if (row.slots.size > 1) {
                    val total = row.slots.sumOf { it.wt.coerceIn(30, 300) }.coerceAtLeast(1)
                    add(Opt(stringResource(R.string.wg_width), "${HomeStore.widgetShare(row, dlg.slot)}%", { HomeStore.setWidgetShare(row.id, dlg.slot, HomeStore.widgetShare(row, dlg.slot) - 1) }, { HomeStore.setWidgetShare(row.id, dlg.slot, HomeStore.widgetShare(row, dlg.slot) + 1) }))
                }
                if (w.type != WIDGET_EMPTY) add(Opt(if (sureWgDel) stringResource(R.string.wg_delete_sure) else stringResource(R.string.wg_delete), danger = true) {
                    if (sureWgDel) {
                        if (w.type == "appwidget") AppWidgets.release(ctx, w.ref)
                        // место остаётся пустым: потом в него можно поставить любой виджет («Вид виджета»)
                        HomeStore.updateSlot(row.id, dlg.slot) { HWidget(WIDGET_EMPTY, n = 0, radius = it.radius, borderW = it.borderW, borderC = it.borderC, borderHover = it.borderHover, wt = it.wt) }
                        close()
                    } else sureWgDel = true
                })
                add(Opt(stringResource(R.string.row_slots), "${row.slots.size}", { HomeStore.setSlotCount(row.id, row.slots.size - 1) }, { HomeStore.setSlotCount(row.id, row.slots.size + 1) }))
                add(Opt(stringResource(R.string.d_row_settings)) { setDlg(Dlg.RowD(row.id)) })
            }
            ListDialog(stringResource(R.string.wg_title), opts, close)
        }

        is Dlg.WidgetType -> {
            val curSlot = HomeStore.row(dlg.rowId)?.slots?.getOrNull(dlg.slot)
            ListDialog(
                stringResource(R.string.wg_type),
                WIDGET_TYPES.map { ty ->
                    Opt(widgetName(ty), if (curSlot?.type == ty) "✓" else "") {
                        if (curSlot?.type == "appwidget") AppWidgets.release(ctx, curSlot.ref)
                        HomeStore.updateSlot(dlg.rowId, dlg.slot) { it.copy(type = ty, n = if (ty == "weather") 5 else 0, ref = "") }
                        setDlg(Dlg.WidgetD(dlg.rowId, dlg.slot))
                    }
                } + Opt(stringResource(R.string.wg_android), if (curSlot?.type == "appwidget") "✓" else "") { setDlg(Dlg.AppWidgetPick(dlg.rowId, dlg.slot)) },
                { setDlg(Dlg.WidgetD(dlg.rowId, dlg.slot)) },
            )
        }

        is Dlg.AppWidgetPick -> {
            val list = remember { AppWidgets.providers(ctx).sortedBy { runCatching { it.loadLabel(ctx.packageManager) }.getOrDefault("").lowercase() } }
            val opts = if (list.isEmpty()) listOf(Opt(stringResource(R.string.wg_none)) { close() })
            else list.map { p ->
                val appName = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p.provider.packageName, 0)).toString() }.getOrDefault("")
                val dens = ctx.resources.displayMetrics.density
                // размер в ячейках (≈ 70 dp на ячейку): виджеты одного приложения с одним названием различаются размером
                val cells = "${((p.minWidth / dens + 30) / 70).toInt().coerceAtLeast(1)}x${((p.minHeight / dens + 30) / 70).toInt().coerceAtLeast(1)}"
                Opt(
                    runCatching { p.loadLabel(ctx.packageManager) }.getOrDefault(p.provider.packageName),
                    (if (appName.isNotBlank()) "$appName · " else "") + cells,
                    icon = runCatching { p.loadIcon(ctx, 0)?.toBitmap(64, 64)?.asImageBitmap() }.getOrNull(),
                ) { close(); addAppWidget(dlg.rowId, dlg.slot, p) }
            }
            ListDialog(stringResource(R.string.wg_android), opts, { setDlg(Dlg.WidgetType(dlg.rowId, dlg.slot)) }, stringResource(R.string.wg_android_hint))
        }

        Dlg.Empty -> {
            val opts = listOf(
                Opt(stringResource(R.string.home_add_row)) { setDlg(Dlg.AddRow(HomeStore.layout.rows.lastOrNull()?.id)) },
                Opt(stringResource(R.string.d_program_settings)) { openAppSettings() },
                Opt(stringResource(R.string.d_screen)) { setDlg(Dlg.Screen) },
                Opt(stringResource(R.string.top_items)) { setDlg(Dlg.Top) },
                Opt(stringResource(R.string.bg_title)) { setDlg(Dlg.Bg) },
            )
            ListDialog(stringResource(R.string.empty_title), opts, close)
        }

        Dlg.Screen -> {
            var sureReset by remember { mutableStateOf(false) }
            var sureOff by remember { mutableStateOf(false) }
            // значения переключателей лежат в настройках, а не в состоянии Compose: счётчик заставляет окно перерисоваться после нажатия
            var rev by remember { mutableStateOf(0) }
            val ringAccent = LocalTh.current.accent
            val homeRingSwatch = remember(Cfg.homeRing, ringAccent) {
                colorDot(if (Cfg.homeRing == 0) ringAccent.toArgb() else Cfg.RING_COLORS[Cfg.homeRing].toInt())
            }
            val modeName = { v: Int, show: Int, hide: Int, auto: Int -> ctx.getString(when (v) { 1 -> show; 2 -> hide; else -> auto }) }
            val opts = listOf(
                // простые «Вкл / Выкл»; без выбора пользователя по умолчанию на ТВ полоска включена, а системные панели выключены, на телефоне наоборот
                Opt(stringResource(R.string.top_mode), (rev * 0).let { if (TopMode.visible(ctx)) on else off },
                    onClick = { TopMode.set(ctx, if (TopMode.visible(ctx)) 2 else 1); rev++ }),
                Opt(stringResource(R.string.sb_status), (rev * 0).let { if (SysBars.hidden(ctx, false)) off else on },
                    onClick = { SysBars.set(ctx, false, if (SysBars.hidden(ctx, false)) 1 else 2); (ctx as? android.app.Activity)?.let { SysBars.apply(it) }; rev++ }),
                Opt(stringResource(R.string.sb_nav), (rev * 0).let { if (SysBars.hidden(ctx, true)) off else on },
                    onClick = { SysBars.set(ctx, true, if (SysBars.hidden(ctx, true)) 1 else 2); (ctx as? android.app.Activity)?.let { SysBars.apply(it) }; rev++ }),
                Opt(stringResource(R.string.top_items)) { setDlg(Dlg.Top) },
                Opt(stringResource(R.string.screen_row_gap), "${HomeStore.layout.rowGap}",
                    { HomeStore.set(HomeStore.layout.copy(rowGap = (HomeStore.layout.rowGap - 1).coerceAtLeast(0))); rev++ },
                    { HomeStore.set(HomeStore.layout.copy(rowGap = (HomeStore.layout.rowGap + 1).coerceAtMost(60))); rev++ }),
                // цвет рамки выделения «Дома» (меню настраивается отдельно в настройках меню); у плитки или виджета свой цвет важнее
                Opt(stringResource(R.string.home_ring_title), stringResource(if (Cfg.homeRing == 0) R.string.ring_default else BORDER_NAMES[Cfg.homeRing]),
                    { Cfg.changeHomeRing((Cfg.homeRing + Cfg.RING_COLORS.size - 1) % Cfg.RING_COLORS.size) },
                    { Cfg.changeHomeRing((Cfg.homeRing + 1) % Cfg.RING_COLORS.size) },
                    icon = homeRingSwatch),
                Opt(stringResource(R.string.bg_title)) { setDlg(Dlg.Bg) },
                Opt(if (sureReset) stringResource(R.string.screen_reset_sure) else stringResource(R.string.screen_reset), danger = true) {
                    if (sureReset) { HomeStore.resetLayout(); close() } else sureReset = true
                },
                Opt(if (sureOff) stringResource(R.string.screen_off_sure) else stringResource(R.string.screen_off_home), danger = true) {
                    if (sureOff) { close(); disableHome() } else sureOff = true
                },
            )
            ListDialog(stringResource(R.string.screen_title), opts, close)
        }

        is Dlg.MoveToRow -> {
            val cur = HomeStore.rowOfItem(dlg.itemId)
            val rows = HomeStore.layout.rows.filter { it.kind == "apps" && it.id != cur?.id }
            val opts = if (rows.isEmpty()) listOf(Opt(stringResource(R.string.d_to_row_none)) { close() })
            else rows.mapIndexed { i, r ->
                Opt(r.title.ifBlank { stringResource(R.string.row_noname) + " " + (HomeStore.layout.rows.indexOf(r) + 1) }) { HomeStore.moveToRow(dlg.itemId, r.id); close() }
            }
            ListDialog(stringResource(R.string.d_to_row), opts, close)
        }


        is Dlg.WidgetCity -> {
            val w = HomeStore.row(dlg.rowId)?.slots?.getOrNull(dlg.slot)
            TextDialog(stringResource(R.string.wg_city), w?.city.orEmpty(), stringResource(R.string.wg_city_hint), { v ->
                HomeStore.updateSlot(dlg.rowId, dlg.slot) { it.copy(city = v) }
                setDlg(Dlg.WidgetD(dlg.rowId, dlg.slot))
            }, { setDlg(Dlg.WidgetD(dlg.rowId, dlg.slot)) })
        }

        is Dlg.Status -> {
            val s = HomeStore.layout.top.status.firstOrNull { it.id == dlg.id }
            if (s == null) { close(); return }
            val opts = buildList {
                add(Opt(stringResource(R.string.d_program_settings)) { openAppSettings() })      // настройки программы — первой строкой
                add(Opt(stringResource(R.string.d_screen)) { setDlg(Dlg.Screen) })      // настройки «Дома» — второй
                add(Opt(stringResource(R.string.d_move)) { close(); startMoveStatus(s.id) })
                if (s.id == "time") {
                    add(Opt(stringResource(R.string.wg_time_fmt), if ((s.opt and 1) != 0) "12 h" else "24 h", onClick = { HomeStore.updateStatus(s.id) { it.copy(opt = it.opt xor 1) } }))
                    add(Opt(stringResource(R.string.wg_seconds), onOff((s.opt and 2) != 0, on, off), onClick = { HomeStore.updateStatus(s.id) { it.copy(opt = it.opt xor 2) } }))
                }
                if (s.id == "date") add(Opt(stringResource(R.string.st_date_fmt), dateSample(s.opt.coerceIn(0, 3)), onClick = { HomeStore.updateStatus(s.id) { it.copy(opt = (it.opt + 1) % 4) } }))
                add(Opt(stringResource(R.string.st_open)) { close(); statusClick(ctx, s.id) })
                add(Opt(stringResource(R.string.st_hide)) { HomeStore.updateStatus(s.id) { it.copy(visible = false) }; close() })
                add(Opt(stringResource(R.string.st_top)) { setDlg(Dlg.Top) })
            }
            ListDialog(statusName(s.id), opts, close)
        }

        Dlg.Top -> {
            val top = HomeStore.layout.top
            val opts = buildList {
                add(Opt(stringResource(R.string.d_program_settings)) { openAppSettings() })      // настройки программы — первой строкой
                add(Opt(stringResource(R.string.d_screen)) { setDlg(Dlg.Screen) })      // настройки «Дома» — второй
                add(Opt(stringResource(R.string.top_big), onOff(top.big, on, off), onClick = { HomeStore.updateTop { it.copy(big = !it.big) } }))
                add(Opt(stringResource(R.string.top_recent), onOff(top.recent, on, off), onClick = { HomeStore.updateTop { it.copy(recent = !it.recent) } }))
                add(Opt(stringResource(R.string.top_clean), onOff(top.clean, on, off), onClick = { HomeStore.updateTop { it.copy(clean = !it.clean) } }))
                add(Opt(stringResource(R.string.top_hdmi), onOff(top.hdmi, on, off), onClick = { HomeStore.updateTop { it.copy(hdmi = !it.hdmi) } }))
                add(Opt(stringResource(R.string.top_search), onOff(top.search, on, off), onClick = { HomeStore.updateTop { it.copy(search = !it.search) } }))
                add(Opt(stringResource(R.string.top_voice_btn), onOff(top.voice, on, off), onClick = { HomeStore.updateTop { it.copy(voice = !it.voice) } }))
                add(Opt(stringResource(R.string.voice_engine), Voice.label(ctx).take(26), onClick = { Voice.picking.value = true; close() }))
                add(Opt(stringResource(R.string.voice_bind_key), Voice.keyName(Voice.customCode(ctx)).ifEmpty { stringResource(R.string.voice_key_default) }, onClick = { Voice.capturing.value = true; close() }))
                top.status.forEach { s ->
                    add(Opt(statusName(s.id), onOff(s.visible, stringResource(R.string.top_shown), stringResource(R.string.top_hidden)), onClick = { HomeStore.updateStatus(s.id) { it.copy(visible = !it.visible) } }))
                }
                add(Opt(stringResource(R.string.bg_title)) { setDlg(Dlg.Bg) })
            }
            ListDialog(stringResource(R.string.top_title), opts, close)
        }

        Dlg.Bg -> BgDialogHost(false) { setDlg(Dlg.Top) }

        is Dlg.AppActions -> {
            val e = apps.firstOrNull { it.pkg == dlg.pkg }
            if (e == null) { close(); return }
            // карточка действий привязывается к месту, где вызвана: ставим её у центра экрана
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.Center).size(1.dp)) {
                    AppMenu(e, Cfg.isHidden(e.pkg), { Cfg.toggleHidden(e.pkg) }, {}, {}) { close(); Conn.refresh() }
                }
            }
        }

        // выбор стиля баннера: четыре готовых варианта с превью, нажатие сохраняет баннер плитки
        is Dlg.BannerStyle -> {
            val item = HomeStore.row(dlg.rowId)?.items?.firstOrNull { it.id == dlg.itemId }
            val entry = apps.firstOrNull { it.pkg == item?.ref }
            val back = { setDlg(Dlg.Look(dlg.rowId, dlg.itemId)) }
            if (item == null || entry == null) { close(); return }
            // цвет баннера: 0 по значку, 1..10 готовые цвета, последний свой (код #RRGGBB)
            var bci by remember(item.id) { mutableStateOf(0) }
            var bcCustom by remember(item.id) { mutableStateOf<Int?>(null) }
            var askHex by remember(item.id) { mutableStateOf(false) }
            val color: Int? = if (bci == BORDER_COLORS.size) bcCustom else if (bci == 0) null else BORDER_COLORS[bci].toInt()
            val previews by produceState<List<androidx.compose.ui.graphics.ImageBitmap>>(emptyList(), item.id, color) {
                value = withContext(Dispatchers.Default) { (0 until BannerGen.STYLES).map { BannerGen.make(ctx, entry, it, color).asImageBitmap() } }
            }
            val names = listOf(R.string.bn_s0, R.string.bn_s1, R.string.bn_s2, R.string.bn_s3, R.string.bn_s4, R.string.bn_s5, R.string.bn_s6, R.string.bn_s7)
            val total = BORDER_COLORS.size + 1
            val colorName = when (bci) { 0 -> stringResource(R.string.bn_color_icon); BORDER_COLORS.size -> stringResource(R.string.bn_color_own); else -> stringResource(BORDER_NAMES[bci]) }
            val opts = buildList {
                add(Opt(stringResource(R.string.bn_color), colorName,
                    { bci = (bci + total - 1) % total; if (bci == BORDER_COLORS.size && bcCustom == null) bci = BORDER_COLORS.size - 1 },
                    { bci = (bci + 1) % total; if (bci == BORDER_COLORS.size && bcCustom == null) bci = 0 }))
                add(Opt(stringResource(R.string.bn_color_hex), onClick = { askHex = true }))
                for (s in 0 until BannerGen.STYLES) {
                    add(Opt(stringResource(names[s]), icon = previews.getOrNull(s), wide = true, onClick = {
                        val ok = BannerGen.apply(ctx, dlg.rowId, item, entry, s, color)
                        Toast.makeText(ctx, ctx.getString(if (ok) R.string.bn_done else R.string.failed), Toast.LENGTH_SHORT).show()
                        back()
                    }))
                }
            }
            ListDialog(stringResource(R.string.bn_pick), opts, back)
            if (askHex) TextDialog(stringResource(R.string.bn_color_hex), "", stringResource(R.string.bn_color_hint), { v ->
                val hx = v.trim().removePrefix("#")
                val parsed = runCatching { (0xFF000000L or hx.toLong(16)).toInt() }.getOrNull()
                if (parsed != null && hx.length == 6) { bcCustom = parsed; bci = BORDER_COLORS.size }
                else Toast.makeText(ctx, ctx.getString(R.string.failed), Toast.LENGTH_SHORT).show()
                askHex = false
            }, { askHex = false })
        }

        // предложение: у приложений на «Доме» нет баннера — создать красивые
        is Dlg.BannerOffer -> {
            val pairs = dlg.items.mapNotNull { (rowId, itemId) ->
                val it = HomeStore.row(rowId)?.items?.firstOrNull { x -> x.id == itemId } ?: return@mapNotNull null
                val e = apps.firstOrNull { a -> a.pkg == it.ref } ?: return@mapNotNull null
                Triple(rowId, it, e)
            }
            if (pairs.isEmpty()) { close(); return }
            val names = pairs.joinToString(", ") { it.third.label }
            val opts = listOf(
                Opt(stringResource(R.string.bn_make_all)) {
                    close()
                    scope.launch {
                        var n = 0
                        for ((rowId, it, e) in pairs) {
                            val bmp = withContext(Dispatchers.Default) { runCatching { BannerGen.make(ctx, e, 0) }.getOrNull() }
                            if (bmp != null && HomeImg.saveBitmap(ctx, it.id, bmp)) { HomeStore.updateItem(rowId, it.id) { x -> x.copy(img = true) }; n++ }
                        }
                        Toast.makeText(ctx, ctx.getString(R.string.bn_done_n, n), Toast.LENGTH_SHORT).show()
                    }
                },
                Opt(stringResource(R.string.bn_later)) { close() },
                Opt(stringResource(R.string.bn_never)) { BannerGen.setNever(ctx, true); close() },
            )
            ListDialog(stringResource(R.string.bn_offer_title, pairs.size), opts, close, hint = names + "\n" + stringResource(R.string.bn_offer_hint))
        }
    }
}

private fun sourceName(ctx: android.content.Context, auto: String): String = when {
    auto == "watch_next" -> ctx.getString(R.string.home_watch_next)
    auto == "mine:fav" -> ctx.getString(R.string.mine_src_fav)
    auto == "mine:seen" -> ctx.getString(R.string.mine_src_seen)
    auto.startsWith("pkg:") -> runCatching {
        val p = auto.removePrefix("pkg:")
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString()
    }.getOrDefault(auto.removePrefix("pkg:"))
    auto.startsWith("ch:") -> auto.removePrefix("ch:").substringAfter(':')
    auto.startsWith("chm:") -> runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(auto.removePrefix("chm:").substringBefore('|'), 0)).toString() + " · " + auto.substringAfter('|', "")
    }.getOrDefault(auto)
    auto.startsWith("cat:") -> runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(auto.removePrefix("cat:").substringBefore('|'), 0)).toString()
    }.getOrDefault(auto.removePrefix("cat:").substringBefore('|'))
    else -> ""
}

/**
 * Выбор фона: один и тот же для «Дома» (menu = false) и для меню приложений (menu = true).
 * Цвет с готовыми вариантами и своим кодом, живой градиент, картинка, видео, анимация, подгонка и затемнение.
 */
@Composable
internal fun BgDialogHost(menu: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var step by remember { mutableStateOf("main") }
    var pickKind by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun cur() = if (menu) HomeStore.menuBg else HomeStore.layout.bg
    fun put(b: HBg) { if (menu) HomeStore.changeMenuBg(b) else HomeStore.setBg(b) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        val kind = pickKind
        pickKind = null
        if (uri != null && kind != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { if (kind == "image") HomeBgFiles.saveImage(ctx, uri, menu) else HomeBgFiles.saveMedia(ctx, uri, menu) }
            if (ok) put(cur().copy(type = kind))
            Toast.makeText(ctx, ctx.getString(if (ok) R.string.icon_saved else R.string.failed), Toast.LENGTH_SHORT).show()
        }
    }
    fun pick(kind: String) {
        pickKind = kind
        runCatching { picker.launch(if (kind == "video") "video/*" else "image/*") }.onFailure {
            pickKind = null
            Toast.makeText(ctx, ctx.getString(R.string.icon_no_picker), Toast.LENGTH_LONG).show()
        }
    }
    val bg = cur()
    when (step) {
        "color" -> {
            val presets = listOf(
                0xFF000000 to R.string.c_black, 0xFF0B0B10 to R.string.c_dark, 0xFF1E293B to R.string.c_slate, 0xFF0F2A44 to R.string.c_navy,
                0xFF1E3A2F to R.string.c_green, 0xFF3B1F2E to R.string.c_wine, 0xFF2A2142 to R.string.c_purple, 0xFF3B2A1A to R.string.c_brown,
                0xFFF1F5F9 to R.string.c_light,
            )
            val opts = presets.map { (c, n) ->
                Opt(stringResource(n), if (bg.type == "color" && bg.color == c) "✓" else "") { put(bg.copy(type = "color", color = c)); step = "main" }
            } + Opt(stringResource(R.string.bg_hex), "#%06X".format(bg.color and 0xFFFFFF)) { step = "hex" }
            ListDialog(stringResource(R.string.bg_color), opts, { step = "main" })
        }
        "hex" -> TextDialog(stringResource(R.string.bg_hex), "#%06X".format(bg.color and 0xFFFFFF), stringResource(R.string.bg_hex_hint), { v ->
            val c = runCatching { (android.graphics.Color.parseColor(if (v.startsWith("#")) v else "#$v").toLong() and 0xFFFFFFFFL) or 0xFF000000L }.getOrNull()
            if (c != null) put(bg.copy(type = if (bg.type == "gradient") "gradient" else "color", color = c))
            else Toast.makeText(ctx, ctx.getString(R.string.bg_hex_bad), Toast.LENGTH_SHORT).show()
            step = "main"
        }, { step = "color" })
        else -> {
            val typeName = when (bg.type) {
                "color" -> stringResource(R.string.bg_color); "gradient" -> stringResource(R.string.bg_gradient); "image" -> stringResource(R.string.bg_image)
                "video" -> stringResource(R.string.bg_video); "anim" -> stringResource(R.string.bg_anim); else -> stringResource(R.string.bg_none)
            }
            val opts = buildList {
                add(Opt(stringResource(R.string.bg_none), if (bg.type == "none") "✓" else "") { put(bg.copy(type = "none")) })
                add(Opt(stringResource(R.string.bg_color), if (bg.type == "color") "✓" else "") { step = "color" })
                add(Opt(stringResource(R.string.bg_gradient), if (bg.type == "gradient") "✓" else "") { put(bg.copy(type = "gradient")) })
                add(Opt(stringResource(R.string.bg_image), if (bg.type == "image") "✓" else "") { pick("image") })
                add(Opt(stringResource(R.string.bg_video), if (bg.type == "video") "✓" else "") { pick("video") })
                add(Opt(stringResource(R.string.bg_anim), if (bg.type == "anim") "✓" else "") { pick("anim") })
                if (bg.type == "image" || bg.type == "video" || bg.type == "anim")
                    add(Opt(stringResource(R.string.bg_fit), if (bg.fit == "stretch") stringResource(R.string.bg_fit_stretch) else stringResource(R.string.bg_fit_crop),
                        onClick = { put(bg.copy(fit = if (bg.fit == "stretch") "crop" else "stretch")) }))
                if (bg.type != "none") add(Opt(stringResource(R.string.bg_dim), "${bg.dim}%",
                    { put(bg.copy(dim = (bg.dim - 1).coerceAtLeast(0))) }, { put(bg.copy(dim = (bg.dim + 1).coerceAtMost(80))) }))
            }
            ListDialog(stringResource(if (menu) R.string.bg_menu_title else R.string.bg_title) + ": " + typeName, opts, onClose, stringResource(R.string.bg_hint))
        }
    }
}
