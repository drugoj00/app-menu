package app.tvapps

import android.view.SoundEffectConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * 9.0: системный звук касания на нажатия по плиткам, кнопкам и строкам (как в ADB-phon 10.5). Звук играет само нажатие, один раз;
 * системная настройка «Звук касания» учитывается (playSoundEffect сам молчит, если она выключена); свой переключатель в «Интерфейсе» (по умолчанию включён).
 */
object ClickSound {
    @Volatile private var last = 0L
    fun play(v: View) {
        if (!Cfg.clickSound) return
        val t = android.os.SystemClock.uptimeMillis()
        if (t - last < 90) return
        last = t
        runCatching { v.playSoundEffect(SoundEffectConstants.CLICK) }
    }
}

/** Действие «издать звук нажатия» для текущего окна; вызывать перед выполнением нажатия. */
@Composable
fun rememberClickSound(): () -> Unit {
    val view = LocalView.current
    return remember(view) { { ClickSound.play(view) } }
}
