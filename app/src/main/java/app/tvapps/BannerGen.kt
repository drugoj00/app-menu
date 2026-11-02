package app.tvapps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap

/**
 * Свой баннер для приложения без баннера: цвет берётся из значка, на нём значок и название (640×360, как карточка 16:9).
 * Стили: 0 цвет значка, 1 тёмный со свечением, 2 светлый, 3 значок по центру.
 */
object BannerGen {
    const val W = 640
    const val H = 360
    const val STYLES = 8
    private const val OFFER_KEY = "banner_offered"
    private const val NEVER_KEY = "banner_never"

    /** Насыщенный цвет значка: среднее по ярким цветным точкам; у серых значков берётся бирюзовый цвет программы. */
    fun dominant(icon: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(icon, 32, 32, true)
        var r = 0.0; var g = 0.0; var b = 0.0; var w = 0.0
        var ar = 0.0; var ag = 0.0; var ab = 0.0; var an = 0
        val hsv = FloatArray(3)
        for (y in 0 until 32) for (x in 0 until 32) {
            val p = s.getPixel(x, y)
            if (Color.alpha(p) < 128) continue
            ar += Color.red(p); ag += Color.green(p); ab += Color.blue(p); an++
            Color.colorToHSV(p, hsv)
            val k = (hsv[1] * hsv[2]).toDouble()
            if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue
            r += Color.red(p) * k; g += Color.green(p) * k; b += Color.blue(p) * k; w += k
        }
        val base = when {
            w > 1.0 -> Color.rgb((r / w).toInt(), (g / w).toInt(), (b / w).toInt())
            an > 0 -> Color.rgb((ar / an).toInt(), (ag / an).toInt(), (ab / an).toInt())
            else -> 0xFF0F766E.toInt()
        }
        Color.colorToHSV(base, hsv)
        hsv[1] = if (w > 1.0) hsv[1].coerceIn(0.45f, 0.95f) else hsv[1].coerceAtMost(0.25f)
        hsv[2] = hsv[2].coerceIn(0.5f, 0.88f)
        return Color.HSVToColor(hsv)
    }

    private fun lighten(c: Int, k: Float) = ColorUtils.blendARGB(c, Color.WHITE, k)
    private fun darken(c: Int, k: Float) = ColorUtils.blendARGB(c, Color.BLACK, k)

    private fun textLayout(text: String, color: Int, width: Int, startSize: Float, align: Layout.Alignment): StaticLayout {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        var size = startSize
        var l: StaticLayout
        while (true) {
            p.textSize = size
            l = StaticLayout.Builder.obtain(text, 0, text.length, p, width).setAlignment(align).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
            // слово не должно рваться: уменьшаем, пока самое длинное слово не помещается
            val longest = text.split(' ', '-', '.').maxOfOrNull { p.measureText(it) } ?: 0f
            val cut = (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 }
            if ((longest <= width && !cut) || size <= 24f) break
            size -= 3f
        }
        return l
    }

    fun make(ctx: Context, e: Entry, style: Int, color: Int? = null): Bitmap = make(e.icon.toBitmap(256, 256), e.label, style, color)

    /** color: свой цвет баннера (ARGB); null: цвет берётся из значка. */
    fun make(icon: Bitmap, label: String, style: Int, color: Int? = null): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val c = color ?: dominant(icon)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val ip = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val full = RectF(0f, 0f, W.toFloat(), H.toFloat())
        fun gradient(a: Int, b: Int, angle: Boolean = true) {
            fill.shader = if (angle) LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), a, b, Shader.TileMode.CLAMP)
            else LinearGradient(0f, 0f, 0f, H.toFloat(), a, b, Shader.TileMode.CLAMP)
            cv.drawRect(full, fill); fill.shader = null
        }
        fun glow(cx: Float, cy: Float, r: Float, col: Int, a: Int) {
            fill.shader = RadialGradient(cx, cy, r, ColorUtils.setAlphaComponent(col, a), ColorUtils.setAlphaComponent(col, 0), Shader.TileMode.CLAMP)
            cv.drawRect(full, fill); fill.shader = null
        }
        fun drawIcon(cx: Float, cy: Float, size: Float, alpha: Int = 255, rot: Float = 0f, shadow: Boolean = false) {
            if (shadow) {
                fill.color = Color.argb(70, 0, 0, 0)
                cv.drawOval(RectF(cx - size * 0.48f, cy + size * 0.36f, cx + size * 0.48f, cy + size * 0.6f), fill)
            }
            ip.alpha = alpha
            cv.save(); cv.rotate(rot, cx, cy)
            cv.drawBitmap(icon, null, RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2), ip)
            cv.restore()
        }
        fun drawText(l: StaticLayout, x: Float, cy: Float) { cv.save(); cv.translate(x, cy - l.height / 2f); l.draw(cv); cv.restore() }

        when (style) {
            0 -> {                                   // цвет значка: диагональный градиент, большой бледный значок позади
                gradient(lighten(c, 0.12f), darken(c, 0.55f))
                drawIcon(W - 70f, H - 40f, 380f, alpha = 40, rot = -14f)
                glow(150f, 150f, 260f, Color.WHITE, 70)
                drawIcon(150f, 170f, 170f, shadow = true)
                drawText(textLayout(label, Color.WHITE, 330, 56f, Layout.Alignment.ALIGN_NORMAL), 266f, 180f)
            }
            1 -> {                                   // тёмный фон и свечение цвета значка
                gradient(0xFF1A1D26.toInt(), 0xFF090A0E.toInt(), angle = false)
                glow(170f, 175f, 280f, c, 190)
                fill.color = ColorUtils.setAlphaComponent(c, 255); cv.drawRect(0f, H - 10f, W.toFloat(), H.toFloat(), fill)
                drawIcon(170f, 170f, 180f, shadow = true)
                drawText(textLayout(label, Color.WHITE, 320, 54f, Layout.Alignment.ALIGN_NORMAL), 285f, 172f)
            }
            2 -> {                                   // светлый: пастель цвета значка
                gradient(lighten(c, 0.88f), lighten(c, 0.6f))
                drawIcon(W - 60f, 70f, 330f, alpha = 55, rot = 12f)
                drawIcon(150f, 170f, 170f, shadow = true)
                drawText(textLayout(label, darken(c, 0.62f), 330, 56f, Layout.Alignment.ALIGN_NORMAL), 266f, 175f)
            }
            4 -> {                                   // две краски: диагональное деление
                fill.color = c; cv.drawRect(full, fill)
                val p = android.graphics.Path().apply { moveTo(W * 0.55f, 0f); lineTo(W.toFloat(), 0f); lineTo(W.toFloat(), H.toFloat()); lineTo(W * 0.35f, H.toFloat()); close() }
                fill.color = darken(c, 0.45f); cv.drawPath(p, fill)
                drawIcon(150f, 170f, 175f, shadow = true)
                drawText(textLayout(label, Color.WHITE, 250, 52f, Layout.Alignment.ALIGN_NORMAL), 262f, 175f)
            }
            5 -> {                                   // точки: фон цвета с узором из точек
                gradient(lighten(c, 0.05f), darken(c, 0.35f))
                fill.color = ColorUtils.setAlphaComponent(Color.WHITE, 38)
                var y = 20f; var rw = 0
                while (y < H) { var x = if (rw % 2 == 0) 20f else 50f; while (x < W) { cv.drawCircle(x, y, 7f, fill); x += 60f }; y += 40f; rw++ }
                drawIcon(150f, 170f, 175f, shadow = true)
                drawText(textLayout(label, Color.WHITE, 330, 56f, Layout.Alignment.ALIGN_NORMAL), 266f, 175f)
            }
            6 -> {                                   // рамка: белая рамка по краю, цвет внутри
                gradient(lighten(c, 0.1f), darken(c, 0.4f), angle = false)
                val st = Paint(Paint.ANTI_ALIAS_FLAG); st.style = Paint.Style.STROKE; st.strokeWidth = 12f; st.color = Color.WHITE
                cv.drawRoundRect(RectF(14f, 14f, W - 14f, H - 14f), 28f, 28f, st)
                drawIcon(W / 2f, 135f, 170f, shadow = true)
                drawText(textLayout(label, Color.WHITE, 520, 48f, Layout.Alignment.ALIGN_CENTER), 60f, 290f)
            }
            7 -> {                                   // круг: большой светлый круг справа, значок в нём
                fill.color = darken(c, 0.35f); cv.drawRect(full, fill)
                fill.color = lighten(c, 0.15f); cv.drawCircle(W - 120f, H / 2f, 230f, fill)
                drawIcon(W - 120f, H / 2f, 210f, shadow = true)
                drawText(textLayout(label, Color.WHITE, 340, 56f, Layout.Alignment.ALIGN_NORMAL), 40f, 180f)
            }
            else -> {                                // значок по центру, название под ним
                gradient(lighten(c, 0.1f), darken(c, 0.5f), angle = false)
                glow(W / 2f, 130f, 300f, Color.WHITE, 90)
                drawIcon(W / 2f, 125f, 190f, shadow = true)
                drawText(textLayout(label, Color.WHITE, 560, 50f, Layout.Alignment.ALIGN_CENTER), 40f, 300f)
            }
        }
        return out
    }

    // ------------------------------------------------------------ предложение создать баннер

    private fun offered(ctx: Context): Set<String> = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE).getStringSet(OFFER_KEY, emptySet()) ?: emptySet()

    fun never(ctx: Context) = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE).getBoolean(NEVER_KEY, false)
    fun setNever(ctx: Context, v: Boolean) { ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE).edit().putBoolean(NEVER_KEY, v).apply() }

    fun markOffered(ctx: Context, pkgs: Collection<String>) {
        val sp = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE)
        sp.edit().putStringSet(OFFER_KEY, offered(ctx) + pkgs).apply()
    }

    /**
     * Плитки в режиме «баннер» без своей картинки, у приложений которых нет баннера и которые ещё не предлагали (идентификаторы плиток).
     * Вызывать не из главного потока.
     */
    fun missing(ctx: Context, apps: List<Entry>): List<Pair<String, HItem>> {
        if (never(ctx)) return emptyList()
        val done = offered(ctx)
        val out = ArrayList<Pair<String, HItem>>()
        for (r in HomeStore.layout.rows) for (it in r.items) {
            if (it.kind != "app" || it.mode != "banner" || it.img || it.ref in done) continue
            val e = apps.firstOrNull { a -> a.pkg == it.ref } ?: continue
            if (Banners.load(ctx, e) == null) out += r.id to it
        }
        return out
    }

    /** Создаёт и сохраняет баннер плитки; true — получилось. */
    fun apply(ctx: Context, rowId: String, item: HItem, e: Entry, style: Int, color: Int? = null): Boolean {
        val ok = runCatching { HomeImg.saveBitmap(ctx, item.id, make(ctx, e, style, color)) }.getOrDefault(false)
        if (ok) HomeStore.updateItem(rowId, item.id) { it.copy(img = true) }
        return ok
    }
}
