package app.tvapps

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Форматирование времени с запоминанием SimpleDateFormat (15.1, плавность): часы «Дома» раз в секунду создавали по 3-6 новых SimpleDateFormat
 * (каждый тянет за собой календарь и таблицы символов), на приставке это были миллисекунды главного потока в каждую секунду.
 * Форматтеры хранятся по потокам (SimpleDateFormat не потокобезопасен); смена часового пояса видна по идентификатору пояса в ключе.
 */
internal object DFmt {
    private val cache = object : ThreadLocal<HashMap<String, SimpleDateFormat>>() { override fun initialValue() = HashMap<String, SimpleDateFormat>() }

    fun format(pattern: String, loc: Locale, ms: Long): String {
        val tz = TimeZone.getDefault().id
        val key = pattern + "|" + loc.toString() + "|" + tz
        val m = cache.get()!!
        val f = m.getOrPut(key) { if (m.size > 64) m.clear(); SimpleDateFormat(pattern, loc) }
        return f.format(Date(ms))
    }
}
