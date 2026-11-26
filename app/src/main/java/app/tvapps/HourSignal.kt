package app.tvapps

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.mutableIntStateOf
import java.io.File

/**
 * 20.0 (просьба владелицы 26.11.2026): сигнал каждый час, пока «Дом» на экране: кукушка (на циферблате из дверок выходит птица, настоящий звук
 * по числу часов), голосом («Сейчас 15 часов») или куранты (столько ударов колокола, сколько часов). Звуки: настоящие записи кукушки и колокола
 * (res/raw, авторы в NOTICE.md) или СВОЙ файл, выбранный в «Часы и будильник». Режим лежит в битах 14-15 числа opt элемента «Часы по центру»:
 * 0 выкл, 1 кукушка, 2 голосом, 3 куранты. Часы работы (с и до) и громкость: настройки clk_from, clk_to, clk_vol. Здесь же звуки будильника.
 */
object HourSignal {
    /** Растёт при каждой кукушке: птица на циферблате запускает показ и выглядывает [count] раз. */
    val tick = mutableIntStateOf(0)
    /** Насколько открыты дверцы в часах (0 закрыты, 1 открыты): циферблат рисует створки, кукушка выходит из проёма. */
    val door = androidx.compose.runtime.mutableFloatStateOf(0f)
    @Volatile var count = 0
    private const val SR = 22050
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    fun mode(opt: Int) = (opt shr 14) and 3
    fun withMode(opt: Int, v: Int) = (opt and (3 shl 14).inv()) or ((v and 3) shl 14)

    // ---------------------------------------------------------------- настройки сигнала

    /** Любое изменение настроек сигнала и будильников: окна, читающие их, перерисовываются. */
    val rev = mutableIntStateOf(0)
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("tvapps", Context.MODE_PRIVATE)
    fun fromH(ctx: Context) = sp(ctx).getInt("clk_from", 0)
    fun toH(ctx: Context) = sp(ctx).getInt("clk_to", 0)
    fun setWindow(ctx: Context, from: Int, to: Int) { sp(ctx).edit().putInt("clk_from", from.mod(24)).putInt("clk_to", to.mod(24)).apply(); rev.intValue++ }
    fun volume(ctx: Context) = sp(ctx).getInt("clk_vol", 100)
    fun setVolume(ctx: Context, v: Int) { sp(ctx).edit().putInt("clk_vol", v.coerceIn(10, 100)).apply(); rev.intValue++ }

    /** Сигнал разрешён в этот час: с [fromH] до [toH] (не включая); если часы равны, сигнал весь день; можно через полночь (с 22 до 7). */
    fun inWindow(ctx: Context, hour24: Int): Boolean {
        val f = fromH(ctx); val t = toH(ctx)
        return if (f == t) true else if (f < t) hour24 in f until t else (hour24 >= f || hour24 < t)
    }

    /** Свой звук: kind "cuckoo" или "chime". Файл копируется в память программы (SoundPickActivity). */
    fun customFile(ctx: Context, kind: String): File? = File(ctx.applicationContext.filesDir, "own_snd_$kind").takeIf { it.isFile && it.length() > 0 }
    fun customName(ctx: Context, kind: String): String = sp(ctx).getString("clk_name_$kind", "") ?: ""
    fun setCustom(ctx: Context, kind: String, name: String) { sp(ctx).edit().putString("clk_name_$kind", name).apply(); rev.intValue++ }
    fun clearCustom(ctx: Context, kind: String) { File(ctx.applicationContext.filesDir, "own_snd_$kind").delete(); setCustom(ctx, kind, "") }

    // ---------------------------------------------------------------- запуск

    /**
     * [bird]: на экране есть циферблат, кукушка спускается по лестнице: звук «ку-ку» начинается после спуска.
     * [auto]: сигнал по часам (учитываются часы работы); проверка в настройках и будильник [auto] не ставят.
     */
    fun fire(ctx: Context, mode: Int, hour24: Int, bird: Boolean = false, auto: Boolean = false) {
        val app = ctx.applicationContext
        if (auto && !inWindow(app, hour24)) return
        val n12 = if (hour24 % 12 == 0) 12 else hour24 % 12
        when (mode) {
            1 -> {
                count = n12
                if (bird) tick.intValue++
                Thread {
                    if (bird) Thread.sleep((LADDER_MS + WALK_MS).toLong())
                    val own = customFile(app, "cuckoo")
                    if (own != null) playFileTimes(app, own, n12, CUCKOO_MS.toLong(), volume(app)) else play(cuckooPcm(app, n12, volume(app)), AudioAttributes.USAGE_MEDIA)
                }.start()
            }
            2 -> speakText(app, phrase(hour24))
            3 -> Thread {
                val own = customFile(app, "chime")
                if (own != null) playFileTimes(app, own, n12, 4000L, volume(app)) else play(bellPcm(app, n12, volume(app)), AudioAttributes.USAGE_MEDIA)
            }.start()
        }
    }

    /** Звук будильника по кругу в отдельном потоке до [stop]: kind 0 кукушка, 1 куранты, 2 голосом, 3 свой файл (кукушки или курантов, что выбрано). */
    fun alarmLoop(ctx: Context, kind: Int, hour24: Int, minute: Int, stop: () -> Boolean) {
        val app = ctx.applicationContext
        Thread {
            while (!stop()) {
                when (kind) {
                    1 -> { val own = customFile(app, "chime"); if (own != null) playFileTimes(app, own, 2, 3000L, 100) else play(bellPcm(app, 3, 100), AudioAttributes.USAGE_ALARM) }
                    2 -> { speakText(app, "Пора вставать. Сейчас $hour24 " + hoursWord(hour24) + " " + minute + " " + minutesWord(minute)); sleepSlow(stop, 7000) }
                    3 -> { val own = customFile(app, "cuckoo") ?: customFile(app, "chime"); if (own != null) playFileTimes(app, own, 1, 1000L, 100) else play(cuckooPcm(app, 3, 100), AudioAttributes.USAGE_ALARM) }
                    else -> play(cuckooPcm(app, 3, 100), AudioAttributes.USAGE_ALARM)
                }
                sleepSlow(stop, 2500)
            }
        }.start()
    }

    private fun sleepSlow(stop: () -> Boolean, ms: Int) { var w = 0; while (w < ms && !stop()) { Thread.sleep(100); w += 100 } }

    // ---------------------------------------------------------------- звук из файла res/raw и своего файла

    /** Звук из файла res/raw (WAV, 16 бит, моно, 22050 Гц): записи кукушки и колокола, авторы в NOTICE.md. */
    private fun loadWav(ctx: Context, res: Int): ShortArray {
        val b = ctx.resources.openRawResource(res).use { it.readBytes() }
        var p = 12
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.US_ASCII)
            val sz = (b[p + 4].toInt() and 255) or ((b[p + 5].toInt() and 255) shl 8) or ((b[p + 6].toInt() and 255) shl 16) or ((b[p + 7].toInt() and 255) shl 24)
            if (id == "data") {
                val n = minOf(sz, b.size - p - 8) / 2
                return ShortArray(n) { ((b[p + 8 + it * 2].toInt() and 255) or (b[p + 9 + it * 2].toInt() shl 8)).toShort() }
            }
            p += 8 + sz + (sz and 1)
        }
        return ShortArray(0)
    }

    /** Накладывает запись [src] на [out] с отступа [at] (секунды). */
    private fun mix(out: FloatArray, src: ShortArray, at: Float) {
        val s0 = (at * SR).toInt()
        for (i in src.indices) { val j = s0 + i; if (j < out.size) out[j] += src[i].toFloat() }
    }

    /** Настоящая кукушка часов по числу часов, каждая через [CUCKOO_MS] (птица на циферблате выглядывает в такт). */
    private fun cuckooPcm(ctx: Context, n: Int, vol: Int): ShortArray {
        val call = loadWav(ctx, R.raw.cuckoo_call)
        val out = FloatArray((SR * (CUCKOO_MS / 1000f * n + 0.5f)).toInt() + call.size)
        for (k in 0 until n) mix(out, call, 0.05f + k * CUCKOO_MS / 1000f)
        return toPcm(out, 0.95f * vol / 100f)
    }

    /** Куранты: столько ударов большого колокола, сколько часов (не больше 12), удар каждые 4 с, хвосты накладываются. */
    private fun bellPcm(ctx: Context, n: Int, vol: Int): ShortArray {
        val strike = loadWav(ctx, R.raw.bell_strike)
        val strikes = n.coerceIn(1, 12)
        val out = FloatArray((SR * 4.0f * (strikes - 1)).toInt() + strike.size)
        for (s in 0 until strikes) mix(out, strike, s * 4.0f)
        return toPcm(out, 0.95f * vol / 100f)
    }

    private fun toPcm(f: FloatArray, peak: Float): ShortArray {
        var m = 0f
        for (v in f) { val a = if (v < 0) -v else v; if (a > m) m = a }
        val k = if (m > 0f) peak * 32000f / m else 0f
        return ShortArray(f.size) { (f[it] * k).toInt().coerceIn(-32000, 32000).toShort() }
    }

    /** Свой файл (любой звуковой формат, который понимает Android) [times] раз подряд, с паузой между началами [gapMs]. Блокирует поток. */
    private fun playFileTimes(ctx: Context, f: File, times: Int, gapMs: Long, vol: Int) {
        for (i in 0 until times) {
            val t0 = System.currentTimeMillis()
            runCatching {
                val mp = MediaPlayer()
                mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                mp.setDataSource(f.absolutePath)
                mp.prepare()
                mp.setVolume(vol / 100f, vol / 100f)
                val latch = java.util.concurrent.CountDownLatch(1)
                mp.setOnCompletionListener { latch.countDown() }
                mp.start()
                latch.await(minOf(mp.duration.toLong() + 500L, 20_000L), java.util.concurrent.TimeUnit.MILLISECONDS)
                mp.release()
            }
            val rest = gapMs - (System.currentTimeMillis() - t0)
            if (i < times - 1 && rest > 0) Thread.sleep(rest)
        }
    }

    private fun play(pcm: ShortArray, usage: Int) {
        android.util.Log.i("HourSignal", "play: " + pcm.size + " отсчётов, usage=" + usage)
        runCatching {
            val at = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            at.write(pcm, 0, pcm.size)
            at.play()
            Thread.sleep(pcm.size * 1000L / SR + 300)
            at.release()
        }.onFailure { android.util.Log.e("HourSignal", "ошибка звука", it) }
    }

    // ---------------------------------------------------------------- голос

    private fun hoursWord(h: Int): String = if (h % 10 == 1 && h != 11) "час" else if (h % 10 in 2..4 && h !in 12..14) "часа" else "часов"
    private fun minutesWord(m: Int): String = if (m % 10 == 1 && m != 11) "минута" else if (m % 10 in 2..4 && m !in 12..14) "минуты" else "минут"

    private fun phrase(h: Int): String {
        val lang = AppLoc.locale().language
        return when (lang) {
            "ru" -> when (h) {
                0 -> "Полночь"; 12 -> "Полдень"
                else -> "Сейчас $h " + hoursWord(h)
            }
            "uk" -> when (h) {
                0 -> "Північ"; 12 -> "Полудень"
                else -> "Зараз $h " + (if (h % 10 == 1 && h != 11) "година" else if (h % 10 in 2..4 && h !in 12..14) "години" else "годин")
            }
            else -> if (h == 0) "Midnight" else if (h == 12) "Noon" else "It is $h o'clock"
        }
    }

    private fun speakText(app: Context, text: String) {
        val t = tts
        if (t != null && ttsReady) { t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hour"); return }
        tts = TextToSpeech(app) { st ->
            if (st == TextToSpeech.SUCCESS) {
                ttsReady = true
                runCatching { tts?.language = AppLoc.locale() }
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hour")
            } else Thread { play(bellPcm(app, 1, volume(app)), AudioAttributes.USAGE_MEDIA) }.start()     // голосовой движок недоступен: хотя бы удар
        }
    }
}
