package app.tvapps

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/** Цвета экрана; тема выбирается в настройках: тёмная, чёрная (для OLED), светлая или как в системе, плюс цвет акцента. */
data class TC(
    val dark: Boolean,
    val bg: Color, val tile: Color, val tileFocus: Color, val text: Color, val sub: Color, val hint: Color,
    val border: Color, val accent: Color, val chipSel: Color,
)

private val DarkTC = TC(
    true, Color(0xFF0B0B10), Color(0xFF12121A), Color(0xFF1E293B), Color.White, Color(0xFF94A3B8), Color(0xFF64748B),
    Color(0xFF334155), Color(0xFF2DD4BF), Color(0xFF0F766E),
)
private val BlackTC = DarkTC.copy(bg = Color(0xFF000000), tile = Color(0xFF0A0A0A), border = Color(0xFF2A2A2A))
private val LightTC = TC(
    false, Color(0xFFF1F5F9), Color(0xFFFFFFFF), Color(0xFFD7EEEC), Color(0xFF111827), Color(0xFF475569), Color(0xFF64748B),
    Color(0xFFCBD5E1), Color(0xFF0F766E), Color(0xFF99E0D8),
)

/** Цвет акцента: для тёмных тем (яркий, выбранный, фокус) и для светлой. */
private class Accent(val dk: Long, val dkSel: Long, val dkFocus: Long, val lt: Long, val ltSel: Long, val ltFocus: Long)

private val Accents = linkedMapOf(
    "teal" to Accent(0xFF2DD4BF, 0xFF0F766E, 0xFF1E293B, 0xFF0F766E, 0xFF99E0D8, 0xFFD7EEEC),
    "blue" to Accent(0xFF60A5FA, 0xFF1D4ED8, 0xFF1E3A5F, 0xFF1D4ED8, 0xFFBFDBFE, 0xFFDBEAFE),
    "orange" to Accent(0xFFFB923C, 0xFFC2410C, 0xFF3B2A1A, 0xFFC2410C, 0xFFFED7AA, 0xFFFFEDD5),
    "purple" to Accent(0xFFA78BFA, 0xFF6D28D9, 0xFF2A2142, 0xFF6D28D9, 0xFFDDD6FE, 0xFFEDE9FE),
    "pink" to Accent(0xFFF472B6, 0xFFBE185D, 0xFF3A1F2E, 0xFFBE185D, 0xFFFBCFE8, 0xFFFCE7F3),
    "green" to Accent(0xFF4ADE80, 0xFF15803D, 0xFF1A3324, 0xFF15803D, 0xFFBBF7D0, 0xFFDCFCE7),
)

val accentKeys: List<String> get() = Accents.keys.toList()

val LocalTh = compositionLocalOf { DarkTC }

@Composable
fun currentTC(): TC {
    val dark = when (Cfg.theme) {
        "light" -> false
        "dark", "black" -> true
        else -> isSystemInDarkTheme()
    }
    val a = Accents[Cfg.accent] ?: Accents.getValue("teal")
    val base = if (!dark) LightTC else if (Cfg.theme == "black") BlackTC else DarkTC
    return if (dark) base.copy(accent = Color(a.dk), chipSel = Color(a.dkSel), tileFocus = Color(a.dkFocus))
    else base.copy(accent = Color(a.lt), chipSel = Color(a.ltSel), tileFocus = Color(a.ltFocus))
}
