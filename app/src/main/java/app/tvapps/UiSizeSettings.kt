@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.tvapps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Правило 28: «Размер интерфейса» — ручной множитель поверх автомасштаба: Мелкий 0,75 · Авто 1,0 · Крупный 1,25 · Очень крупный 1,5, плюс «−/+» шагом 0,05 и «По умолчанию»; показан итоговый коэффициент. */
@Composable
internal fun UiSizeSetting() {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val auto = remember { UiScale.autoK(ctx) }
    val mul = Cfg.uiMul
    val total = UiScale.totalK(ctx, mul)
    fun f(v: Float) = String.format(java.util.Locale.getDefault(), "%.2f", v)
    fun near(a: Float, b: Float) = kotlin.math.abs(a - b) < 0.011f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ui_size_total, f(total), f(auto)), color = t.accent, fontSize = 15.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.ui_sz_small), near(mul, 0.75f)) { Cfg.changeUiMul(0.75f) }
            Chip(stringResource(R.string.ui_sz_auto), near(mul, 1f)) { Cfg.changeUiMul(1f) }
            Chip(stringResource(R.string.ui_sz_big), near(mul, 1.25f)) { Cfg.changeUiMul(1.25f) }
            Chip(stringResource(R.string.ui_sz_huge), near(mul, 1.5f)) { Cfg.changeUiMul(1.5f) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("−", false) { Cfg.changeUiMul(mul - 0.05f) }
            Chip("×" + f(mul), false) { }
            Chip("+", false) { Cfg.changeUiMul(mul + 0.05f) }
            Chip("↺ " + stringResource(R.string.ui_sz_reset), false) { Cfg.changeUiMul(1f) }
        }
    }
}
