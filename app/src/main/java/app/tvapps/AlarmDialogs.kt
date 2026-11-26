package app.tvapps

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/** Список будильников: каждый открывает свои настройки; внизу «Добавить будильник». Хранение и запуск: [Alarms]. */
@Composable
internal fun AlarmsDialog(setDlg: (Dlg?) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val rev = HourSignal.rev.intValue
    val off = stringResource(R.string.off)
    val opts = buildList {
        for (a in Alarms.list(ctx)) add(Opt(Alarms.timeText(a), Alarms.daysLabel(ctx, a.days) + if (a.enabled) "" else " · $off", onClick = { setDlg(Dlg.AlarmEdit(a.id)) }))
        add(Opt(stringResource(R.string.alarm_add)) { val a = Alarms.add(ctx); setDlg(Dlg.AlarmEdit(a.id)) })
    }
    ListDialog(stringResource(R.string.alarms_title), opts, onBack, hint = stringResource(R.string.alarm_limits))
}

/** Один будильник: включён, час, минуты, дни, звук, проверка, удаление. */
@Composable
internal fun AlarmEditDialog(id: String, setDlg: (Dlg?) -> Unit) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val rev = HourSignal.rev.intValue
    val a = Alarms.get(ctx, id)
    if (a == null) { setDlg(Dlg.Alarms); return }
    val on = stringResource(R.string.on); val off = stringResource(R.string.off)
    val names = listOf(R.string.alarm_snd_0, R.string.alarm_snd_1, R.string.alarm_snd_2, R.string.alarm_snd_3).map { stringResource(it) }
    val opts = listOf(
        Opt(stringResource(R.string.alarm_on), if (a.enabled) on else off, onClick = { Alarms.update(ctx, id) { it.copy(enabled = !it.enabled) } }),
        Opt(stringResource(R.string.alarm_hour), "%02d".format(a.hour),
            onDec = { Alarms.update(ctx, id) { it.copy(hour = (it.hour + 23) % 24) } },
            onInc = { Alarms.update(ctx, id) { it.copy(hour = (it.hour + 1) % 24) } },
            onClick = { Alarms.update(ctx, id) { it.copy(hour = (it.hour + 1) % 24) } }),
        Opt(stringResource(R.string.alarm_minute), "%02d".format(a.minute),
            onDec = { Alarms.update(ctx, id) { it.copy(minute = (it.minute + 55) % 60) } },
            onInc = { Alarms.update(ctx, id) { it.copy(minute = (it.minute + 5) % 60) } },
            onClick = { Alarms.update(ctx, id) { it.copy(minute = (it.minute + 5) % 60) } }),
        Opt(stringResource(R.string.alarm_days), Alarms.daysLabel(ctx, a.days), onClick = { Alarms.update(ctx, id) { it.copy(days = Alarms.nextDays(it.days)) } }),
        Opt(stringResource(R.string.alarm_sound), names[a.sound.coerceIn(0, 3)], onClick = { Alarms.update(ctx, id) { it.copy(sound = (it.sound + 1) % 4) } }),
        Opt(stringResource(R.string.alarm_test)) {
            val t0 = System.currentTimeMillis()
            HourSignal.alarmLoop(ctx, a.sound, a.hour, a.minute) { System.currentTimeMillis() - t0 > 6000L }
        },
        Opt(stringResource(R.string.alarm_delete), danger = true, onClick = { Alarms.remove(ctx, id); setDlg(Dlg.Alarms) }),
    )
    ListDialog(Alarms.timeText(a), opts, { setDlg(Dlg.Alarms) })
}

/** Настройки «Каждый час» (часы работы, громкость, проверка, свои звуки) и вход в будильники: добавляются к окну «Часы по центру». */
@Composable
internal fun hourSignalOpts(mode: Int, setDlg: (Dlg?) -> Unit): List<Opt> {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val rev = HourSignal.rev.intValue
    val out = ArrayList<Opt>()
    if (mode != 0) {
        val f = HourSignal.fromH(ctx); val t = HourSignal.toH(ctx)
        out += Opt(stringResource(R.string.hour_from), "%02d:00".format(f),
            onDec = { HourSignal.setWindow(ctx, f + 23, t) }, onInc = { HourSignal.setWindow(ctx, f + 1, t) }, onClick = { HourSignal.setWindow(ctx, f + 1, t) })
        out += Opt(stringResource(R.string.hour_to), "%02d:00".format(t),
            onDec = { HourSignal.setWindow(ctx, f, t + 23) }, onInc = { HourSignal.setWindow(ctx, f, t + 1) }, onClick = { HourSignal.setWindow(ctx, f, t + 1) })
        val v = HourSignal.volume(ctx)
        out += Opt(stringResource(R.string.hour_vol), "$v%",
            onDec = { HourSignal.setVolume(ctx, v - 10) }, onInc = { HourSignal.setVolume(ctx, v + 10) }, onClick = { HourSignal.setVolume(ctx, if (v >= 100) 10 else v + 10) })
        out += Opt(stringResource(R.string.hour_test)) { HourSignal.fire(ctx, mode, java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) }
        val none = stringResource(R.string.hour_own_none)
        for ((kind, res) in listOf("cuckoo" to R.string.hour_own_cuckoo, "chime" to R.string.hour_own_chime)) {
            val name = if (HourSignal.customFile(ctx, kind) != null) HourSignal.customName(ctx, kind).ifBlank { "audio" } else none
            out += Opt(stringResource(res), name,
                onRemove = if (HourSignal.customFile(ctx, kind) != null) ({ HourSignal.clearCustom(ctx, kind) }) else null,
                onClick = { ctx.startActivity(android.content.Intent(ctx, SoundPickActivity::class.java).putExtra("kind", kind)) })
        }
    }
    out += Opt(stringResource(R.string.alarm_menu)) { setDlg(Dlg.Alarms) }
    return out
}
