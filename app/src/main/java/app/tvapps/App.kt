package app.tvapps

import android.app.Application

class App : Application() {
    companion object { @Volatile var instance: App? = null }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        CacheBudget.trim(level)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 18.11.2026: запасной уровень защиты от урезанного системного масштаба анимации для декларативных Compose-анимаций
        // (animate*AsState, animateContentSize, Modifier.animateItem и т.п.), которым нельзя подставить свой withContext —
        // см. подробности у AnimScale (Desks.kt). Максимально рано, до первой отрисовки Compose.
        AnimScale.forceFull()
        // замена пакета: нет APK/ресурсов, не падаем в цикле (CodeGuard)
        if (!CodeGuard.ready(this)) { CodeGuard.bail(this); return }
        // падение Compose «BringIntoViewRequester ... before parents are placed» (8.0, Android 7.1) не роняет процесс, см. CrashGuard
        runCatching { CrashGuard.install(this); CrashGuard.debugHook(this) }
        // Состояние Compose (mutableStateOf) у объектов создаётся здесь, в главном потоке и вне рисования: если объект впервые создаётся
        // во время рисования или в фоновом потоке, возможна ошибка «Reading a state that was created after the snapshot was taken»
        // (19.11.2026: в runCatching, как остальные запуски ниже: сбой подготовки одного объекта не должен ронять процесс при каждом запуске)
        runCatching { warmComposeState() }
        // корни Let's Encrypt для Android 7.1.0 и старше (погода, обновления, F-Droid)
        runCatching { TlsFix.install(this) }
        // расписание фоновой проверки обновлений (если включено)
        runCatching { Cfg.init(this) }
        runCatching { Lite.init(this) }
        runCatching { UpdSched.apply(this) }
        // 15.0: копии в сеть и синхронизация: слушатель изменений настроек, расписание и проверка «пора ли» через полминуты после запуска (сеть успевает подняться)
        runCatching { BkEngine.watch(this); BkSched.apply(this) }
        runCatching { android.os.Handler(mainLooper).postDelayed({ BkEngine.runAutoAsync(this) }, 30_000L) }
        // защита главного экрана: частые перезапуски при отключённом родном экране возвращают его
        runCatching { HomeControl.guardOnProcessStart(this) }
        // шифрование Conscrypt и ключ ADB создаются при первом обращении к ADB (Adb.ensure), а не при каждом запуске: экономит память
        runCatching { Adb.init(this) }
        // заставка по бездействию: если включена, служба и сторож поднимаются вместе с процессом программы
        runCatching { SaverGuard.startIfEnabled(this, "процесс программы") }
        runCatching { Integrity.warm(this) }
        // уборка: временные файлы старше часа или от прежней версии, единое место копий, не больше двух копий на приложение
        runCatching { Housekeeping.onStart(this) }
    }

    private fun warmComposeState() {
        val all = listOf<Any>(CardStore, Cfg, Desks, HomeNav, HomeStore, WifiReceive, LabelCache, NavReq, Conn, TopUi, Pairing, Lite, DenseGrid, SelfUpd, UpdOpen, Voice, WxSrc)
        android.util.Log.i("TVApps", "состояние Compose подготовлено: " + all.size)
    }
}
