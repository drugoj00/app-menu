package app.tvapps

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Окно будильника поверх экрана блокировки: время, звук по кругу (до 3 минут), «Выключить» и «Ещё 10 минут».
 * Пульт: фокус сразу на «Выключить», ОК гасит; «Назад» тоже выключает.
 */
class AlarmActivity : ComponentActivity() {
    @Volatile private var stopped = false
    private var id = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
        id = intent.getStringExtra("id").orEmpty()
        val h = intent.getIntExtra("h", 7); val m = intent.getIntExtra("m", 0); val kind = intent.getIntExtra("k", 0)
        val started = System.currentTimeMillis()
        HourSignal.alarmLoop(this, kind, h, m) { stopped || System.currentTimeMillis() - started > 180_000L }
        setContent {
            val req = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { req.requestFocus() } }
            Box(Modifier.fillMaxSize().background(Color(0xFF0B1220)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%02d:%02d".format(h, m), color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.alarm_title), color = Color(0xFF94A3B8), fontSize = 24.sp)
                    Spacer(Modifier.height(36.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Button(onClick = { dismiss() }, modifier = Modifier.focusRequester(req), shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E), contentColor = Color.Black)) {
                            Text(stringResource(R.string.alarm_stop), fontSize = 24.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                        }
                        Button(onClick = { snooze() }, shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155), contentColor = Color.White)) {
                            Text(stringResource(R.string.alarm_snooze), fontSize = 24.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                        }
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        // ОК на пульте или Enter гасят будильник, даже если фокус ещё не встал на кнопку
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER || keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) { dismiss(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun dismiss() { stopped = true; finishAndRemoveTask() }
    private fun snooze() { Alarms.snooze(this, id, 10); stopped = true; finishAndRemoveTask() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { dismiss() }

    override fun onDestroy() { stopped = true; super.onDestroy() }
}
