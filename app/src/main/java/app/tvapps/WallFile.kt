package app.tvapps

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 27.11.2026 (слово владелицы: «только надо свой формат а не гиф»; «если фотка с самсунга уже движущаяся, то её только в свой формат»).
 * Файл живых обоев Меню приложений (.amwall): внутри картинка и то, что нужно для движения.
 *  - «3d»: картинка и карта глубины (256 на 256). На другом устройстве объём работает без скачивания нейросети;
 *  - «motion»: картинка и движение из «живого» фото телефона (Samsung, Google): внутри такого снимка лежит короткое видео.
 * Расчёты без Android, чтобы проверяться обычными тестами.
 */
internal object WallFile {
    const val EXT = "amwall"
    const val LIMIT = 80 * 1024 * 1024
    private const val FORMAT = "appmenu-wallpaper"
    private val BRANDS = setOf("mp42", "mp41", "isom", "iso2", "iso4", "iso5", "iso6", "avc1", "M4V ", "qt  ", "MSNV", "3gp4", "3gp5")

    class Content(val type: String, val picture: ByteArray, val depth: ByteArray? = null, val video: ByteArray? = null)

    private fun text(b: ByteArray, at: Int, n: Int) = String(b, at, n, Charsets.ISO_8859_1)

    /** Где внутри снимка начинается вложенное видео (блок «ftyp» видеофайла после самой картинки); -1: это обычная картинка. */
    fun videoStart(b: ByteArray): Int {
        if (b.size < 64) return -1
        val jpeg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
        val heic = text(b, 4, 4) == "ftyp" && text(b, 8, 4).let { it.startsWith("hei") || it.startsWith("mif") }
        if (!jpeg && !heic) return -1
        var i = 16
        while (i <= b.size - 12) {
            if (b[i] == 'f'.code.toByte() && b[i + 1] == 't'.code.toByte() && b[i + 2] == 'y'.code.toByte() && b[i + 3] == 'p'.code.toByte()) {
                var size = 0
                for (k in i - 4 until i) size = (size shl 8) or (b[k].toInt() and 255)
                if (size in 12..64 && text(b, i + 4, 4) in BRANDS) return i - 4
            }
            i++
        }
        return -1
    }

    fun pack(out: OutputStream, c: Content) {
        ZipOutputStream(out).use { zip ->
            fun put(name: String, data: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
            put("manifest.txt", "$FORMAT\n1\n${c.type}\n".toByteArray())
            put("picture", c.picture)
            c.depth?.let { put("depth.bin", it) }
            c.video?.let { put("motion.mp4", it) }
        }
    }

    private fun unpack(b: ByteArray): Content? = runCatching {
        val parts = HashMap<String, ByteArray>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(b)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name !in setOf("manifest.txt", "picture", "depth.bin", "motion.mp4")) continue
                val out = ByteArrayOutputStream()
                val buf = ByteArray(65536)
                while (true) {
                    val n = zip.read(buf)
                    if (n < 0) break
                    total += n
                    require(total <= LIMIT)
                    out.write(buf, 0, n)
                }
                parts[e.name] = out.toByteArray()
            }
        }
        val head = String(parts.getValue("manifest.txt")).split('\n')
        require(head[0] == FORMAT)
        val type = head[2]
        val depth = parts["depth.bin"]
        val video = parts["motion.mp4"]
        require((type == "3d" && depth != null && depth.size == 256 * 256) || (type == "motion" && video != null))
        Content(type, parts.getValue("picture"), depth, video)
    }.getOrNull()

    /** Наш файл или «живое» фото телефона; null: обычная картинка или посторонний файл. */
    fun read(b: ByteArray): Content? {
        if (b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()) return unpack(b)
        val at = videoStart(b)
        if (at <= 0) return null
        // картинка: начало файла до видео (у HEIC читателю нужен весь файл, лишнее в конце он пропускает)
        val picture = if (b[0] == 0xFF.toByte()) b.copyOfRange(0, at) else b
        return Content("motion", picture, null, b.copyOfRange(at, b.size))
    }
}
