package app.tvapps

import android.content.Context

/** Логика запоминания положения в меню (чистая, проверяется юнит-тестом). */
object MenuMemoryLogic {
    /**
     * На какую плитку вернуть фокус. [savedPkg] == null: фокуса не было (касание), тогда первая плитка. Если запомненной плитки уже нет
     * (приложение удалено, скрыто, список другой), берётся плитка на прежнем месте ([savedIndex]), то есть ближайшая.
     */
    fun focusTarget(list: List<String>, savedPkg: String?, savedIndex: Int): String? {
        if (list.isEmpty()) return null
        if (savedPkg == null) return list.first()
        if (savedPkg in list) return savedPkg
        return list[savedIndex.coerceIn(0, list.lastIndex)]
    }

    /** К какой плитке (индексу) прокрутить сетку: к первой видимой на момент ухода; если её нет, к тому же месту списка. */
    fun scrollIndex(list: List<String>, firstPkg: String?, firstIndex: Int): Int {
        if (list.isEmpty()) return 0
        if (firstPkg != null) { val i = list.indexOf(firstPkg); if (i >= 0) return i }
        return firstIndex.coerceIn(0, list.lastIndex)
    }

    /** Кандидат на фокус в экране настроек: подпись кнопки и её положение по вертикали в прокручиваемом содержимом. */
    data class Cand(val text: String, val y: Float)

    /** Какую кнопку настроек вернуть в фокус: та же подпись и ближайшее положение (не дальше [tol] px); null, если такой кнопки уже нет. */
    fun pickChip(c: List<Cand>, text: String?, y: Float, tol: Float = 12f): Int? {
        if (text == null) return null
        var best: Int? = null
        var bd = Float.MAX_VALUE
        for (i in c.indices) {
            if (c[i].text != text) continue
            val d = Math.abs(c[i].y - y)
            if (d <= tol && d < bd) { best = i; bd = d }
        }
        return best
    }

    /** Раздел настроек, открываемый при возврате: только известные значения. */
    fun settingsSub(saved: String?): String = when (saved) { "hidden" -> "hidden"; "excl" -> "excl"; else -> "main" }

    /** Нужно ли восстанавливать прокрутку: есть запомненное место и оно отличается от текущего. */
    fun needsScroll(hasSaved: Boolean, target: Int, current: Int): Boolean = hasSaved && target != current
}

/**
 * Положение в меню: плитка в фокусе (пакет и номер) и первая видимая плитка с отступом (прокрутка). Хранится в памяти процесса (переживает пересоздание окна)
 * и в настройках программы (переживает выгрузку из памяти). Сбрасывается при выходе из меню кнопкой «Назад», если не включено «Запоминать положение после выхода».
 */
object MenuMemory {
    var focusPkg: String? = null; private set
    var focusIndex: Int = 0; private set
    var firstPkg: String? = null; private set
    var firstIndex: Int = 0; private set
    var firstOffset: Int = 0; private set
    /** Экран настроек: раздел, прокрутка основного раздела и кнопка в фокусе (подпись и положение). */
    var settingsSub: String = "main"; private set
    var settingsScroll: Int = 0; private set
    var settingsFocusText: String? = null; private set
    var settingsFocusY: Float = 0f; private set
    private var loaded = false

    val hasScroll: Boolean get() = firstPkg != null || firstIndex > 0 || firstOffset > 0

    /** Один раз за процесс: читает сохранённое положение. */
    fun init(ctx: Context): MenuMemory {
        if (loaded) return this
        loaded = true
        val sp = ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE)
        focusPkg = sp.getString("mm_fpkg", null)
        focusIndex = sp.getInt("mm_fidx", 0)
        firstPkg = sp.getString("mm_spkg", null)
        firstIndex = sp.getInt("mm_sidx", 0)
        firstOffset = sp.getInt("mm_soff", 0)
        settingsSub = MenuMemoryLogic.settingsSub(sp.getString("mm_set_sub", "main"))
        settingsScroll = sp.getInt("mm_set_scroll", 0)
        settingsFocusText = sp.getString("mm_set_ftext", null)
        settingsFocusY = sp.getFloat("mm_set_fy", 0f)
        // столы: после закрытия программы положение не запоминается, если не включено «Запоминать положение после выхода»
        if (Desks.on && !Cfg.rememberPos) restore(null)
        return this
    }

    /** Положение текущего стола: сохраняется при уходе на другой стол. */
    fun snapshot(): DeskMem = DeskMem(focusPkg, focusIndex, firstPkg, firstIndex, firstOffset)

    /** Возвращает положение стола; null означает «сверху, фокуса нет». */
    fun restore(m: DeskMem?) {
        focusPkg = m?.focusPkg; focusIndex = m?.focusIndex ?: 0
        firstPkg = m?.firstPkg; firstIndex = m?.firstIndex ?: 0; firstOffset = m?.firstOffset ?: 0
    }

    fun setFocus(pkg: String, index: Int) { focusPkg = pkg; focusIndex = index.coerceAtLeast(0) }

    fun setScroll(pkg: String?, index: Int, offset: Int) { firstPkg = pkg; firstIndex = index.coerceAtLeast(0); firstOffset = offset.coerceAtLeast(0) }

    fun setSettingsSub(sub: String) { settingsSub = MenuMemoryLogic.settingsSub(sub) }
    fun setSettingsScroll(v: Int) { settingsScroll = v.coerceAtLeast(0) }
    fun setSettingsFocus(text: String, y: Float) { settingsFocusText = text; settingsFocusY = y }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("tvapps", Context.MODE_PRIVATE).edit()
            .putString("mm_fpkg", focusPkg).putInt("mm_fidx", focusIndex).putString("mm_spkg", firstPkg).putInt("mm_sidx", firstIndex).putInt("mm_soff", firstOffset)
            .putString("mm_set_sub", settingsSub).putInt("mm_set_scroll", settingsScroll).putString("mm_set_ftext", settingsFocusText).putFloat("mm_set_fy", settingsFocusY).apply()
    }

    /** Выход из меню: следующее открытие начинается с начала. */
    fun clear(ctx: Context) {
        focusPkg = null; focusIndex = 0; firstPkg = null; firstIndex = 0; firstOffset = 0
        settingsSub = "main"; settingsScroll = 0; settingsFocusText = null; settingsFocusY = 0f
        save(ctx)
    }
}
