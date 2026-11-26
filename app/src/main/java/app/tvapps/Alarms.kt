package app.tvapps

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Будильники (просьба владелицы 26.11.2026: «будильник с памятью и автозапуском при включении приставки или телефона»).
 * Хранятся в настройках программы (память: переживают перезапуск и обновление), ставятся в системный AlarmManager (setAlarmClock),
 * заново ставятся при включении устройства, после обновления программы, смены времени и часового пояса.
 * Ограничение: из полностью выключенного устройства (не сна) будильник не включит; приставка в режиме сна просыпается по сигналу, если прошивка это разрешает.
 */
data class Alarm(
    val id: String,
    val hour: Int = 7,
    val minute: Int = 0,
    val enabled: Boolean = true,
    /** Дни недели: биты 1 пн, 2 вт, 4 ср, 8 чт, 16 пт, 32 сб, 64 вс; 0 - один раз (после срабатывания выключается). */
    val days: Int = 127,
    /** Звук: 0 кукушка, 1 куранты, 2 голосом, 3 свой файл. */
    val sound: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("h", hour).put("m", minute).put("on", enabled).put("d", days).put("k", sound)
}

object Alarms {
    const val ACTION = "app.tvapps.ALARM"
    private const val KEY = "alarms"
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("tvapps", Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Alarm> = runCatching {
        val a = JSONArray(sp(ctx).getString(KEY, "[]") ?: "[]")
        (0 until a.length()).map { val o = a.getJSONObject(it); Alarm(o.getString("id"), o.optInt("h", 7), o.optInt("m", 0), o.optBoolean("on", true), o.optInt("d", 127), o.optInt("k", 0)) }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, l: List<Alarm>) {
        sp(ctx).edit().putString(KEY, JSONArray(l.map { it.toJson() }).toString()).apply()
        HourSignal.rev.intValue++
        rearmAll(ctx)
    }

    fun get(ctx: Context, id: String): Alarm? = list(ctx).firstOrNull { it.id == id }
    fun add(ctx: Context): Alarm { val a = Alarm("a" + System.currentTimeMillis()); save(ctx, list(ctx) + a); return a }
    fun update(ctx: Context, id: String, f: (Alarm) -> Alarm) = save(ctx, list(ctx).map { if (it.id == id) f(it) else it })
    fun remove(ctx: Context, id: String) { cancel(ctx, id); save(ctx, list(ctx).filter { it.id != id }) }

    /** Ближайший момент срабатывания, мс; null: будильник выключен. */
    fun nextTime(a: Alarm, now: Long = System.currentTimeMillis()): Long? {
        if (!a.enabled) return null
        for (d in 0..7) {
            val c = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, d); set(Calendar.HOUR_OF_DAY, a.hour); set(Calendar.MINUTE, a.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            if (c.timeInMillis <= now) continue
            val bit = 1 shl ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7)      // понедельник = бит 1
            if (a.days == 0 || (a.days and bit) != 0) return c.timeInMillis
        }
        return null
    }

    private fun pi(ctx: Context, id: String): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION).putExtra("id", id)
        return PendingIntent.getBroadcast(ctx.applicationContext, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun cancel(ctx: Context, id: String) {
        runCatching { (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(ctx, id)) }
    }

    /** Ставит в систему все включённые будильники (и снимает выключенные). Вызывается при любом изменении, загрузке и обновлении. */
    fun rearmAll(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (a in list(app)) {
            val t = nextTime(a)
            if (t == null) { cancel(app, a.id); continue }
            arm(app, am, a.id, t)
        }
    }

    private fun arm(app: Context, am: AlarmManager, id: String, at: Long) {
        val show = PendingIntent.getActivity(app, 7, Intent(app, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val ok = runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi(app, id)) }.isSuccess
        if (!ok) runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(app, id)) }      // нет права на точные будильники: срабатывает чуть неточно
    }

    /** Отложить ещё на [minutes] минут (повтор той же записи). */
    fun snooze(ctx: Context, id: String, minutes: Int) {
        val app = ctx.applicationContext
        arm(app, app.getSystemService(Context.ALARM_SERVICE) as AlarmManager, id, System.currentTimeMillis() + minutes * 60_000L)
    }

    /** Сработал: разовый выключается, остальные встают на следующий день. */
    fun fired(ctx: Context, id: String) {
        val a = get(ctx, id) ?: return
        if (a.days == 0) update(ctx, id) { it.copy(enabled = false) } else rearmAll(ctx)
    }

    fun daysLabel(ctx: Context, d: Int): String = when (d) {
        0 -> ctx.getString(R.string.alarm_once)
        127 -> ctx.getString(R.string.alarm_every)
        31 -> ctx.getString(R.string.alarm_weekdays)
        96 -> ctx.getString(R.string.alarm_weekend)
        else -> ctx.getString(R.string.alarm_custom)
    }

    /** Порядок выбора дней по кругу: каждый день, будни, выходные, один раз. */
    fun nextDays(d: Int): Int = when (d) { 127 -> 31; 31 -> 96; 96 -> 0; else -> 127 }

    fun timeText(a: Alarm) = "%02d:%02d".format(a.hour, a.minute)
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val app = ctx.applicationContext
        val a = Alarms.get(app, id) ?: return
        Alarms.fired(app, id)
        runCatching {
            app.startActivity(Intent(app, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("id", id).putExtra("h", a.hour).putExtra("m", a.minute).putExtra("k", a.sound))
        }
    }
}
