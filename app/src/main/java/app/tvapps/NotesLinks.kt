package app.tvapps

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * 18.0 (просьба владелицы 24.11.2026): в «Что нового» слово-ссылка из описания выпуска (разметка [слово](https://адрес), как на GitHub)
 * показывается словом, подчёркнутым цветом акцента, и открывается по нажатию; в самом тексте разметки не видно.
 */
object NotesLinks {
    private val re = Regex("""\[([^\]\n]+)]\((https?://[^)\s]+)\)""")
    const val TAG = "URL"

    fun has(s: String) = re.containsMatchIn(s)

    fun annotated(s: String, accent: Color): AnnotatedString = buildAnnotatedString {
        var pos = 0
        for (m in re.findAll(s)) {
            append(s.substring(pos, m.range.first))
            pushStringAnnotation(TAG, m.groupValues[2])
            withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(m.groupValues[1]) }
            pop()
            pos = m.range.last + 1
        }
        append(s.substring(pos))
    }
}
