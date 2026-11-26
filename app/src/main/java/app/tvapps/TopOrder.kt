package app.tvapps

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * 20.0 (просьба владелицы 26.11.2026: «верхняя строка один блок кнопок»; Евгений: дата и год вкл/выкл, «Очистка» с левой стороны
 * на правую, часы по центру или справа): все элементы верхней строки (кнопки, поле поиска, значки состояния, пустые места) стоят в
 * одном порядке, который можно менять. Пустое место "sp1", "sp2"... растягивается и отодвигает соседей: так часы встают по центру.
 * Если порядок не сохранён, он прежний: кнопки слева, поле поиска, значки состояния справа.
 */
internal object TopOrder {
    val BUTTONS = listOf("menu", "recent", "dock", "clean", "hdmi", "search")

    fun isGap(id: String) = id.startsWith("sp")

    /** Полный порядок: сохранённый (без неизвестных) и недостающие элементы в прежнем порядке. */
    fun normalize(top: HTop): List<String> {
        val statusIds = top.status.map { it.id }.filter { it != "cclock" }
        val known = BUTTONS + statusIds
        val saved = top.order.filter { it in known || isGap(it) }.distinct()
        val base = if (saved.isEmpty()) known else saved
        return base + known.filter { it !in base }
    }

    /** Занимает ли элемент место в строке сейчас (включён и есть на этом устройстве). */
    fun visible(ctx: Context, top: HTop, id: String): Boolean = when (id) {
        "menu" -> top.menu && !Cfg.menuOff
        "recent" -> top.recent
        "dock" -> HomeDock.btn(ctx)
        "clean" -> top.clean
        "hdmi" -> top.hdmi && TopMode.isTv(ctx)
        "search" -> top.search || (top.voice && Voice.available(ctx))
        else -> if (isGap(id)) true else top.status.firstOrNull { it.id == id }?.visible == true
    }

    /** Включить или выключить элемент. */
    fun toggle(ctx: Context, id: String) {
        when (id) {
            "menu" -> HomeStore.updateTop { it.copy(menu = !it.menu) }
            "recent" -> HomeStore.updateTop { it.copy(recent = !it.recent) }
            "dock" -> HomeDock.setBtn(ctx, !HomeDock.btn(ctx))
            "clean" -> HomeStore.updateTop { it.copy(clean = !it.clean) }
            "hdmi" -> HomeStore.updateTop { it.copy(hdmi = !it.hdmi) }
            "search" -> HomeStore.updateTop { it.copy(search = !it.search) }
            else -> if (!isGap(id)) HomeStore.updateStatus(id) { it.copy(visible = !it.visible) }
        }
    }

    val SCALES = listOf(70, 85, 100, 115, 130, 150)
    fun nextScale(cur: Int): Int = SCALES.firstOrNull { it > cur } ?: SCALES.first()
}

@Composable
internal fun topItemLabel(id: String): String = when (id) {
    "menu" -> stringResource(R.string.top_menu)
    "recent" -> stringResource(R.string.top_recent)
    "dock" -> stringResource(R.string.dock_btn)
    "clean" -> stringResource(R.string.top_clean)
    "hdmi" -> stringResource(R.string.top_hdmi)
    "search" -> stringResource(R.string.top_search)
    else -> if (TopOrder.isGap(id)) stringResource(R.string.ord_gap) + " " + id.removePrefix("sp") else statusName(id)
}
