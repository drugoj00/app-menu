package app.tvapps

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.LruCache

/**
 * Предел кэшей картинок зависит от памяти устройства (ActivityManager.memoryClass): на приставке с 1 ГБ кэши не раздуваются до 40 МБ.
 * При уходе в фон (onTrimMemory) кэши картинок освобождаются, на глубоком фоне уходят и фон «Дома», и готовые подписи.
 */
object CacheBudget {
    private val caches = java.util.Collections.synchronizedList(ArrayList<LruCache<*, *>>())

    /** Размер кэша в байтах: не больше [capMb], но и не больше шестой части кучи приложения (не меньше 6 МБ). */
    fun bytes(capMb: Int): Int {
        val mc = runCatching { (App.instance?.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass }.getOrNull() ?: 192
        return minOf(capMb, maxOf(6, mc / 6)) * 1024 * 1024
    }

    fun <K : Any, V : Any> track(c: LruCache<K, V>): LruCache<K, V> { caches.add(c); return c }

    fun trim(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) synchronized(caches) { caches.forEach { runCatching { it.evictAll() } } }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) { BgMem.image = null; BgMem.menuImage = null; LabelCache.clear() }
    }
}
