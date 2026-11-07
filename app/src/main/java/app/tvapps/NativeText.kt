package app.tvapps

import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Итог раскладки текста окна (то, что раньше давал TextLayoutResult): число строк, обрезан ли текст, конец каждой строки.
 */
class NativeLayout(val lineCount: Int, val hasVisualOverflow: Boolean, private val ends: IntArray) {
    @Suppress("UNUSED_PARAMETER")
    fun getLineEnd(lineIndex: Int, visibleEnd: Boolean = false): Int = ends[lineIndex]
}

private class TextHolder {
    var layout: StaticLayout? = null
    var offsetY = 0f
}

/**
 * Текст окон приложения: рисуется ТЕМ ЖЕ способом, что подписи значков в сетке (LabelCache.build): нативный TextPaint (AppFont.textPaint:
 * тот же шрифт, ANTI_ALIAS, целые ширины букв, без подмешивания «жирного шрифта» системы и без типографики Material) и StaticLayout
 * (BREAK_STRATEGY_SIMPLE, без переносов по слогам). Поэтому слова в карточке действий, панели ярлыков, окнах «Дома» и настройках выходят
 * буква в букву такими же, как подписи под значками. Подпись для доступности (semantics) сохраняется.
 * Сигнатура как у Text из Material 3, чтобы вызовы в окнах не менялись; в этом пакете эта функция перекрывает Material Text.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    @Suppress("UNUSED_PARAMETER") fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((NativeLayout) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    val density = LocalDensity.current
    val fs = fontSize.takeIf { it != TextUnit.Unspecified } ?: style.fontSize.takeIf { it != TextUnit.Unspecified } ?: 14.sp
    val weight = (fontWeight ?: style.fontWeight)?.weight ?: 400
    val italic = (fontStyle ?: style.fontStyle) == FontStyle.Italic
    val ls = letterSpacing.takeIf { it != TextUnit.Unspecified } ?: style.letterSpacing
    val lh = lineHeight.takeIf { it != TextUnit.Unspecified } ?: style.lineHeight
    val align = textAlign ?: style.textAlign
    val deco = textDecoration ?: style.textDecoration
    val col = color.takeIf { it != Color.Unspecified } ?: style.color.takeIf { it != Color.Unspecified } ?: LocalContentColor.current
    val fsPx = with(density) { fs.toPx() }
    val lsPx = if (ls == TextUnit.Unspecified) 0f else with(density) { ls.toPx() }
    val lhPx = if (lh == TextUnit.Unspecified) -1f else with(density) { lh.toPx() }
    val holder = remember { TextHolder() }
    val alignment = when (align) {
        TextAlign.Center -> Layout.Alignment.ALIGN_CENTER
        TextAlign.End, TextAlign.Right -> Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_NORMAL
    }
    val colorInt = col.toArgb()
    // Рисующая часть пересоздаётся при любой смене текста и оформления. Иначе (лямбда запоминается по захваченным holder и colorInt)
    // новая строка той же ширины («16» → «17», «50%» → «51%») пересчитывалась, но не перерисовывалась: счётчики в окнах показывали старое число
    val painter = remember(text, colorInt, fsPx, lsPx, lhPx, weight, italic, deco, align, maxLines, minLines, softWrap, overflow) {
        Modifier.drawBehind {
            val l = holder.layout ?: return@drawBehind
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save()
                nc.translate(0f, holder.offsetY)
                l.paint.color = colorInt
                l.draw(nc)
                nc.restore()
            }
        }
    }
    Layout(
        content = {},
        modifier = modifier
            .semantics { this.text = AnnotatedString(text) }
            .then(if (overflow == TextOverflow.Clip) Modifier.clipToBounds() else Modifier)
            .then(painter),
    ) { _, c: Constraints ->
        val paint: TextPaint = AppFont.textPaint(weight, italic).apply {
            textSize = fsPx
            if (lsPx != 0f && fsPx > 0f) this.letterSpacing = lsPx / fsPx
            isUnderlineText = deco?.contains(TextDecoration.Underline) == true
            isStrikeThruText = deco?.contains(TextDecoration.LineThrough) == true
        }
        val fm = paint.fontMetrics
        val fontLine = fm.descent - fm.ascent
        val lineH = if (lhPx > 0f) lhPx else fontLine
        val extra = if (lhPx > 0f) lhPx - fontLine else 0f
        val limit = if (maxLines < 1) 1 else maxLines
        val bounded = c.hasBoundedWidth
        val maxW = if (bounded) c.maxWidth else 16384
        var src: CharSequence = text
        // без переноса: одна строка, а если шире окна, то многоточие (или обрезка)
        val wrapW = if (softWrap) maxW else 16384
        if (!softWrap && bounded) {
            val natural = paint.measureText(text)
            if (natural > maxW && overflow == TextOverflow.Ellipsis) src = TextUtils.ellipsize(text, paint, maxW.toFloat(), TextUtils.TruncateAt.END)
        }
        fun build(w: Int): StaticLayout =
            StaticLayout.Builder.obtain(src, 0, src.length, paint, max(w, 1))
                .setAlignment(alignment)
                .setMaxLines(if (softWrap) limit else 1)
                .setEllipsize(if (overflow == TextOverflow.Ellipsis && softWrap) TextUtils.TruncateAt.END else null)
                .setEllipsizedWidth(max(w, 1))
                .setIncludePad(false)
                .setLineSpacing(extra, 1f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
        var l = build(wrapW)
        var natural = 0f
        for (i in 0 until l.lineCount) natural = max(natural, l.getLineWidth(i))
        val wantW = ceil(natural.toDouble()).toInt()
        val w = min(max(wantW, c.minWidth), if (bounded) c.maxWidth else Int.MAX_VALUE / 2)
        if (w != wrapW && softWrap) l = build(w)
        val lines = max(l.lineCount, minLines)
        val h = ceil((lines * lineH).toDouble()).toInt()
        holder.layout = l
        holder.offsetY = extra / 2f
        if (onTextLayout != null) {
            val ends = IntArray(l.lineCount) { l.getLineEnd(it) }
            val over = l.lineCount > 0 && (l.getLineEnd(l.lineCount - 1) < src.length || l.getEllipsisCount(l.lineCount - 1) > 0 || (!softWrap && natural > w))
            onTextLayout(NativeLayout(l.lineCount, over, ends))
        }
        layout(w, h.coerceIn(c.minHeight, if (c.hasBoundedHeight) c.maxHeight else Int.MAX_VALUE / 2)) {}
    }
}
