package app.tvapps

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.security.SecureRandom

/**
 * Приём файлов напрямую, без роутера (Wi‑Fi Direct): устройство создаёт группу и становится её владельцем,
 * телефон подключается к ней как к обычной Wi‑Fi сети (по имени и паролю или по QR), затем открывает адрес группы в браузере.
 * Используется тот же сервер приёма (UploadServer), что и для обычной сети.
 */
object DirectGroup {
    class Info(val ssid: String, val pass: String, val ip: String)

    @Volatile private var manager: WifiP2pManager? = null
    @Volatile private var channel: WifiP2pManager.Channel? = null
    @Volatile private var active = false
    private val ui = Handler(Looper.getMainLooper())

    fun supported(ctx: Context): Boolean = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    /** Права, нужные для создания группы: до Android 12 включительно геолокация, с Android 13 — «Устройства поблизости». */
    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf("android.permission.NEARBY_WIFI_DEVICES")
        else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions(ctx: Context): Boolean = permissions().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun reason(code: Int): String = when (code) {
        WifiP2pManager.P2P_UNSUPPORTED -> "p2p-unsupported"
        WifiP2pManager.BUSY -> "busy"
        else -> "error-$code"
    }

    /** Создаёт группу. Результат приходит в главном потоке: либо сведения для подключения, либо код причины (unsupported, busy, noperm, timeout, error-N). */
    @SuppressLint("MissingPermission")
    fun start(ctx0: Context, done: (Info?, String?) -> Unit) {
        val ctx = ctx0.applicationContext
        if (!supported(ctx)) { done(null, "unsupported"); return }
        if (!hasPermissions(ctx)) { done(null, "noperm"); return }
        val wm = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: run { done(null, "unsupported"); return }
        val ch = runCatching { wm.initialize(ctx, Looper.getMainLooper(), null) }.getOrNull() ?: run { done(null, "unsupported"); return }
        manager = wm; channel = ch; active = true
        fun create() {
            val l = object : WifiP2pManager.ActionListener {
                override fun onSuccess() { poll(wm, ch, 0, done) }
                override fun onFailure(code: Int) { active = false; done(null, reason(code)) }
            }
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val rnd = SecureRandom()
                    val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
                    val pass = (1..10).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
                    val cfg = WifiP2pConfig.Builder().setNetworkName("DIRECT-am-AppMenu").setPassphrase(pass).enablePersistentMode(false).build()
                    wm.createGroup(ch, cfg, l)
                } else wm.createGroup(ch, l)
            } catch (e: SecurityException) { active = false; done(null, "noperm") }
            catch (e: Exception) { active = false; done(null, "error-" + (e.message ?: e.javaClass.simpleName)) }
        }
        // если группа уже есть (осталась от прошлого раза или чужая), её нужно снять: иначе createGroup вернёт «занято»
        try {
            wm.requestGroupInfo(ch) { g ->
                if (g != null) wm.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { ui.postDelayed({ create() }, 700) }
                    override fun onFailure(code: Int) { create() }
                }) else create()
            }
        } catch (e: SecurityException) { active = false; done(null, "noperm") }
    }

    @SuppressLint("MissingPermission")
    private fun poll(wm: WifiP2pManager, ch: WifiP2pManager.Channel, n: Int, done: (Info?, String?) -> Unit) {
        if (!active) return
        try {
            wm.requestGroupInfo(ch) { g ->
                val ssid = g?.networkName
                val pass = g?.passphrase
                if (g != null && !ssid.isNullOrEmpty() && !pass.isNullOrEmpty()) {
                    val ip = Lan.p2pAddress() ?: "192.168.49.1"
                    done(Info(ssid, pass, ip), null)
                } else if (n >= 24) { done(null, "timeout") }
                else ui.postDelayed({ poll(wm, ch, n + 1, done) }, 500)
            }
        } catch (e: SecurityException) { done(null, "noperm") }
    }

    /** Снимает группу. Вызывается при закрытии окна приёма. */
    fun stop() {
        active = false
        val wm = manager; val ch = channel
        manager = null; channel = null
        if (wm == null || ch == null) return
        runCatching {
            wm.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { closeChannel(ch) }
                override fun onFailure(code: Int) { closeChannel(ch) }
            })
        }.onFailure { closeChannel(ch) }
    }

    private fun closeChannel(ch: WifiP2pManager.Channel) {
        if (Build.VERSION.SDK_INT >= 27) runCatching { ch.close() }
    }

    /** Строка для QR: телефон предложит подключиться к сети. */
    fun wifiQr(i: Info): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
        return "WIFI:T:WPA;S:${esc(i.ssid)};P:${esc(i.pass)};;"
    }
}

/** Маленький генератор QR-кодов (режим «байты», коррекция L, версии 1–6, маска 0): достаточно для строки подключения к Wi‑Fi. */
object Qr {
    private val capacityData = intArrayOf(0, 19, 34, 55, 80, 108, 136)       // байт данных по версиям
    private val eccPerBlock = intArrayOf(0, 7, 10, 15, 20, 26, 18)
    private val blocks = intArrayOf(0, 1, 1, 1, 1, 1, 2)
    private val align = arrayOf(intArrayOf(), intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30), intArrayOf(6, 34))

    /** Возвращает матрицу модулей (true = тёмный) или null, если текст слишком длинный. */
    fun encode(text: String): Array<BooleanArray>? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var ver = 0
        for (v in 1..6) if ((capacityData[v] * 8 - 12) / 8 >= bytes.size) { ver = v; break }
        if (ver == 0) return null
        val size = 17 + 4 * ver
        // 1. данные
        val bits = ArrayList<Int>()
        fun put(value: Int, n: Int) { for (i in n - 1 downTo 0) bits.add((value ushr i) and 1) }
        put(4, 4); put(bytes.size, 8)
        for (b in bytes) put(b.toInt() and 0xFF, 8)
        val total = capacityData[ver] * 8
        put(0, minOf(4, total - bits.size))
        while (bits.size % 8 != 0) bits.add(0)
        var pad = 0xEC
        while (bits.size < total) { put(pad, 8); pad = if (pad == 0xEC) 0x11 else 0xEC }
        val data = ByteArray(capacityData[ver]) { i -> var v = 0; for (k in 0 until 8) v = (v shl 1) or bits[i * 8 + k]; v.toByte() }
        // 2. коррекция ошибок и перемежение
        val nb = blocks[ver]; val ecc = eccPerBlock[ver]
        val per = data.size / nb
        val div = rsDivisor(ecc)
        val dBlocks = (0 until nb).map { data.copyOfRange(it * per, (it + 1) * per) }
        val eBlocks = dBlocks.map { rsRemainder(it, div) }
        val out = ArrayList<Byte>()
        for (i in 0 until per) for (b in dBlocks) out.add(b[i])
        for (i in 0 until ecc) for (b in eBlocks) out.add(b[i])
        // 3. рисунок
        val m = Array(size) { BooleanArray(size) }
        val fn = Array(size) { BooleanArray(size) }
        fun setFn(x: Int, y: Int, dark: Boolean) { if (x in 0 until size && y in 0 until size) { m[y][x] = dark; fn[y][x] = true } }
        for (i in 0 until size) { setFn(6, i, i % 2 == 0); setFn(i, 6, i % 2 == 0) }
        fun finder(cx: Int, cy: Int) { for (dy in -4..4) for (dx in -4..4) { val d = maxOf(Math.abs(dx), Math.abs(dy)); setFn(cx + dx, cy + dy, d != 2 && d != 4) } }
        finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
        val ap = align[ver]
        for (i in ap.indices) for (j in ap.indices) {
            if ((i == 0 && j == 0) || (i == 0 && j == ap.size - 1) || (i == ap.size - 1 && j == 0)) continue
            for (dy in -2..2) for (dx in -2..2) setFn(ap[i] + dx, ap[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
        }
        // служебные биты формата (коррекция L = 01, маска 0)
        val fmtData = (1 shl 3) or 0
        var rem = fmtData
        for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        val fmt = ((fmtData shl 10) or rem) xor 0x5412
        fun fb(i: Int) = ((fmt ushr i) and 1) != 0
        for (i in 0..5) setFn(8, i, fb(i))
        setFn(8, 7, fb(6)); setFn(8, 8, fb(7)); setFn(7, 8, fb(8))
        for (i in 9..14) setFn(14 - i, 8, fb(i))
        for (i in 0..7) setFn(size - 1 - i, 8, fb(i))
        for (i in 8..14) setFn(8, size - 15 + i, fb(i))
        setFn(8, size - 8, true)
        // данные змейкой
        var idx = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) for (j in 0..1) {
                val x = right - j
                val upward = ((right + 1) and 2) == 0
                val y = if (upward) size - 1 - vert else vert
                if (!fn[y][x] && idx < out.size * 8) {
                    m[y][x] = ((out[idx ushr 3].toInt() ushr (7 - (idx and 7))) and 1) != 0
                    idx++
                }
            }
            right -= 2
        }
        for (y in 0 until size) for (x in 0 until size) if (!fn[y][x] && (x + y) % 2 == 0) m[y][x] = !m[y][x]
        return m
    }

    private fun mul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) { z = (z shl 1) xor ((z ushr 7) * 0x11D); z = z xor (((y ushr i) and 1) * x) }
        return z
    }

    private fun rsDivisor(degree: Int): IntArray {
        val r = IntArray(degree); r[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) { r[j] = mul(r[j], root); if (j + 1 < degree) r[j] = r[j] xor r[j + 1] }
            root = mul(root, 2)
        }
        return r
    }

    private fun rsRemainder(data: ByteArray, div: IntArray): ByteArray {
        val r = IntArray(div.size)
        for (b in data) {
            val f = (b.toInt() and 0xFF) xor r[0]
            for (i in 0 until r.size - 1) r[i] = r[i + 1]
            r[r.size - 1] = 0
            for (i in r.indices) r[i] = r[i] xor mul(div[i], f)
        }
        return ByteArray(r.size) { r[it].toByte() }
    }
}
