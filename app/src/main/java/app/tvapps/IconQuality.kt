package app.tvapps

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.drawable.toBitmap

/**
 * Детектор «пустых» и «чёрных» значков (чистая арифметика по пикселям, без Android: проверяется юнит-тестом).
 * Пример: значок Pulsar до 2.2 в плотности mdpi (магнитола 160 dpi): непрозрачных 669 пикселей из 11664, без фона у слоя adaptive-значка, на экране чёрный квадрат.
 */
object IconPixels {
    /** Доля непрозрачных пикселей ниже этого порога: значок почти пустой. */
    const val MIN_OPAQUE = 0.08f

    /**
     * true, если значок плохой: почти пустой (непрозрачных меньше 8%) или целиком одного тёмного цвета (почти нет разброса яркости).
     * [px] в формате ARGB (как Bitmap.getPixels).
     */
    fun isBad(px: IntArray): Boolean {
        if (px.isEmpty()) return true
        var opaque = 0
        var sum = 0.0
        var sum2 = 0.0
        for (p in px) {
            val a = p ushr 24
            if (a < 40) continue
            opaque++
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            val y = 0.299 * r + 0.587 * g + 0.114 * b
            sum += y; sum2 += y * y
        }
        if (opaque.toFloat() / px.size < MIN_OPAQUE) return true
        val mean = sum / opaque
        val std = Math.sqrt(Math.max(0.0, sum2 / opaque - mean * mean))
        // сплошной тёмный квадрат: яркость почти не меняется и она низкая
        return mean < 45.0 && std < 8.0
    }
}

/**
 * Получение нормального значка приложения независимо от того, как он нарисован в самом приложении.
 * Порядок: на экранах с низкой плотностью (магнитолы, 160 dpi) сначала крупная плотность (xxxhdpi, xxhdpi) из ресурсов приложения, затем значок,
 * который отдаёт система; на обычных сначала системный. Каждый вариант рендерится в 192x192 и проверяется детектором; для adaptive-значка
 * дополнительно рисуется передний слой на цветном фоне. Если все варианты плохие, рисуется запасной значок: скруглённый квадрат цветом по хэшу пакета
 * с первой буквой названия. Вызывается только в фоне при подготовке списка.
 */
object IconQuality {
    private const val OUT = 192

    fun best(densityDpi: Int, pm: PackageManager, ai: ActivityInfo, loaded: Drawable, label: String, pkg: String): Bitmap {
        val sources = ArrayList<() -> Drawable?>()
        val high640: () -> Drawable? = { highDensity(pm, ai, 640) }
        val high480: () -> Drawable? = { highDensity(pm, ai, 480) }
        if (densityDpi < 240) { sources.add(high640); sources.add(high480); sources.add { loaded } }
        else { sources.add { loaded }; sources.add(high640); sources.add(high480) }
        val tried = ArrayList<Drawable>()
        for (s in sources) {
            val d = runCatching { s() }.getOrNull() ?: continue
            tried.add(d)
            val b = runCatching { d.toBitmap(OUT, OUT) }.getOrNull() ?: continue
            if (!isBad(b)) return b
        }
        // adaptive-значок без фона: передний слой поверх цвета по хэшу пакета
        if (Build.VERSION.SDK_INT >= 26) {
            for (d in tried) {
                if (d is android.graphics.drawable.AdaptiveIconDrawable) {
                    val b = runCatching { foregroundOnColor(d, pkg) }.getOrNull() ?: continue
                    if (!isBad(b)) return b
                }
            }
        }
        return letterIcon(label, pkg)
    }

    private fun highDensity(pm: PackageManager, ai: ActivityInfo, dens: Int): Drawable? {
        val app = ai.applicationInfo ?: return null
        val res = pm.getResourcesForApplication(app)
        val id = if (ai.icon != 0) ai.icon else app.icon
        if (id == 0) return null
        @Suppress("DEPRECATION")
        return res.getDrawableForDensity(id, dens, null)
    }

    /** Пустой или чёрный значок (Bitmap любого размера). */
    fun isBadBitmap(b: Bitmap): Boolean = isBad(b)

    private fun isBad(b: Bitmap): Boolean {
        val s = Bitmap.createScaledBitmap(b, 64, 64, false)
        val px = IntArray(64 * 64)
        s.getPixels(px, 0, 64, 0, 0, 64, 64)
        if (s !== b) s.recycle()
        return IconPixels.isBad(px)
    }

    private fun colorFor(pkg: String): Int {
        val h = ((pkg.hashCode() and 0x7fffffff) % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(h, 0.55f, 0.62f))
    }

    @android.annotation.TargetApi(26)
    private fun foregroundOnColor(d: android.graphics.drawable.AdaptiveIconDrawable, pkg: String): Bitmap {
        val bmp = Bitmap.createBitmap(OUT, OUT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorFor(pkg) }
        c.drawRoundRect(RectF(0f, 0f, OUT.toFloat(), OUT.toFloat()), OUT * 0.22f, OUT * 0.22f, p)
        val fg = d.foreground ?: return bmp
        // слои adaptive-значка 108 dp, видимая часть 72 dp: слой в полтора раза больше значка, по центру
        val over = (OUT * 0.25f).toInt()
        c.save()
        fg.setBounds(-over, -over, OUT + over, OUT + over)
        fg.draw(c)
        c.restore()
        return bmp
    }

    /** Запасной значок: скруглённый квадрат цветом по хэшу пакета и первая буква названия. */
    fun letterIcon(label: String, pkg: String): Bitmap {
        val bmp = Bitmap.createBitmap(OUT, OUT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorFor(pkg) }
        c.drawRoundRect(RectF(0f, 0f, OUT.toFloat(), OUT.toFloat()), OUT * 0.22f, OUT * 0.22f, p)
        val ch = label.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = OUT * 0.56f; textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        c.drawText(ch, OUT / 2f, OUT / 2f - (t.descent() + t.ascent()) / 2f, t)
        return bmp
    }
}
