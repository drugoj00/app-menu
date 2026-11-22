package app.tvapps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Полоска прогресса (правило владелицы 20.11.2026): определённая, заполняется слева направо по мере выполнения (frac 0..1),
 * без бегущей анимации, без градиента, без смены цвета при успехе: в конце просто заполненная полоска цвета акцента темы.
 * Системные ProgressIndicator в программе не используются.
 */
@Composable
fun FlatProgress(frac: Float, modifier: Modifier = Modifier) {
    val t = LocalTh.current
    val f = if (frac.isNaN()) 0f else frac.coerceIn(0f, 1f)
    Row(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(t.border)) {
        if (f > 0f) Row(Modifier.fillMaxHeight().fillMaxWidth(f).background(t.accent)) {}
    }
}
