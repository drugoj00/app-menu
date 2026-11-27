package app.tvapps

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class WallFileTest {
    private fun box(brand: String) = byteArrayOf(0, 0, 0, 24) + ("ftyp" + brand).toByteArray() + ByteArray(12) + ByteArray(40) { it.toByte() }
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(300) { (it % 200).toByte() } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    @Test fun motionPhotoIsFoundAndSplit() {
        // «живое» фото: за картинкой метка Samsung и вложенное видео
        val photo = jpeg + "MotionPhoto_Data".toByteArray() + box("mp42")
        val c = WallFile.read(photo)!!
        assertEquals("motion", c.type)
        assertArrayEquals(box("mp42"), c.video)
        assertEquals(0xFF.toByte(), c.picture[0])
    }

    @Test fun plainPictureStaysPlain() {
        assertNull(WallFile.read(jpeg))
        // слово ftyp без настоящего заголовка видео не считается
        assertNull(WallFile.read(jpeg + "....ftypXXXX........".toByteArray() + ByteArray(60)))
        assertNull(WallFile.read(ByteArray(200) { 7 }))
    }

    @Test fun ownFormatRoundTrip() {
        val depth = ByteArray(256 * 256) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        WallFile.pack(out, WallFile.Content("3d", jpeg, depth))
        val c = WallFile.read(out.toByteArray())!!
        assertEquals("3d", c.type)
        assertArrayEquals(jpeg, c.picture)
        assertArrayEquals(depth, c.depth)
        assertNull(c.video)
        // чужой архив не принимается
        val other = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(other).use { it.putNextEntry(java.util.zip.ZipEntry("a.txt")); it.write(1) }
        assertNull(WallFile.read(other.toByteArray()))
    }
}
