package app.tvapps

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast

/**
 * Выбор своего звука для кукушки или курантов (kind = "cuckoo" или "chime"): системный выбор файла, файл копируется в память программы
 * (own_snd_<kind>), чтобы звук играл без доступа к исходному месту. Окно прозрачное: открывается из настроек «Часы по центру».
 */
class SoundPickActivity : Activity() {
    private var kind = "cuckoo"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = intent.getStringExtra("kind") ?: "cuckoo"
        if (savedInstanceState != null) return
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE)
        runCatching { startActivityForResult(Intent.createChooser(i, null), 51) }.onFailure {
            Toast.makeText(this, R.string.icon_no_picker, Toast.LENGTH_LONG).show(); finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri? = data?.data
        if (requestCode == 51 && resultCode == RESULT_OK && uri != null) {
            val ok = runCatching {
                contentResolver.openInputStream(uri)!!.use { src -> java.io.File(filesDir, "own_snd_$kind").outputStream().use { src.copyTo(it) } }
                var name = ""
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: "" }
                HourSignal.setCustom(this, kind, name.ifBlank { "audio" })
            }.isSuccess
            Toast.makeText(this, if (ok) R.string.icon_saved else R.string.failed, Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
