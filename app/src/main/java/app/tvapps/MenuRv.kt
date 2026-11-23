package app.tvapps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 16.0 (плавность, приказ владелицы 23.11.2026: «сетки и списки на RecyclerView, как в ADB-phon»): сетка и список значков «Меню» на RecyclerView
 * с простыми View вместо плиток Compose. Плитка рисуется на Canvas (значок на подложке, готовая раскладка подписи из LabelCache, рамка фокуса,
 * отметка выбора), ViewHolder переиспользуются, идентификаторы стабильные, список меняется через DiffUtil (фокус и положение не теряются).
 * Всё остальное (шапка, диалоги, меню действий, состояние выбора, переноса, столов) остаётся в Compose и общается с сеткой через [MenuRvOps],
 * [MenuRvState] и [MenuRvCtl]. Прежняя сетка на LazyVerticalGrid/LazyColumn оставлена в MainActivity.kt: [MenuRv.ON] = false возвращает её.
 */
internal object MenuRv {
    const val ON = true
}

/** Состояние, которое плитки читают при рисовании. Compose обновляет его (см. [MenuRvSync]) и просит перерисовать плитки. */
internal class MenuRvState {
    @JvmField var selectMode = false
    @JvmField var selected: Set<String> = emptySet()
    @JvmField var moving: String? = null
    @JvmField var mergeReady: String? = null
    @JvmField var dragging = false
    /** Курсор запомнен на этой плитке, пока фокус вне сетки (рамка 2 dp). */
    @JvmField var marked: String? = null
}

/** Всё, что нужно сетке для раскладки и рисования; размеры в пикселях. Равенство (data class) решает, нужна ли перестройка. */
internal data class MenuRvParams(
    val listMode: Boolean,
    val cols: Int,
    val dense: Boolean,
    val padL: Int, val padT: Int, val padR: Int, val padB: Int,
    val gapX: Int, val gapY: Int,
    /** Поля плитки с каждой стороны. */
    val tilePad: Float,
    /** Подложка значка и значок на ней (с подложкой) и без подложки, px. */
    val plate: Float, val iconOn: Float, val iconOff: Float,
    val labelGap: Float,
    val showLabels: Boolean,
    val labelSpec: LabelSpec?,
    val plates: Boolean, val transparentTiles: Boolean,
    val cOverlay: Int,
    val cTile: Int, val cTileFocus: Int, val cChipSel: Int, val cText: Int, val cHint: Int, val cBorder: Int, val cAccent: Int, val cRing: Int,
    val density: Float, val fontScale: Float,
    val focusZoom: Int,
    val listIcon: Float, val listLabelSp: Float,
    val hiddenKey: Int,
    val iconTick: Int,
    val userScroll: Boolean,
)

/** Что сетка сообщает наружу. */
internal class MenuRvOps(
    val click: (Entry) -> Unit,
    val long: (Entry) -> Unit,
    /** Стрелка в режиме переноса: код клавиши. */
    val moveKey: (String, Int) -> Boolean,
    val dragBegin: (Entry) -> Unit,
    val dragMove: (String, Offset) -> Unit,
    val dragEnd: () -> Unit,
    val focused: (String) -> Unit,
    val gridFocus: (Boolean) -> Unit,
    /** Клавиша до плитки (столы: страницы, цифры, меню столов, края сетки). true: съедена. */
    val preKey: (KeyEvent) -> Boolean,
    val scrolled: (Int, Int) -> Unit,
    /** столбцов, строк целиком, высота плитки, высота окна сетки. */
    val layoutInfo: (Int, Int, Int, Int) -> Unit,
    val sound: (View) -> Unit,
)

/** Лёгкие запросы Compose к сетке (положение, фокус, прокрутка). */
internal class MenuRvCtl {
    @JvmField var view: MenuRvView? = null
    @JvmField val state = MenuRvState()
    val items: List<Entry> get() = view?.items ?: emptyList()
    fun refresh() { view?.refreshTiles() }
}

/** Плитка видима: ключ (пакет) и прямоугольник в координатах сетки. */
internal class RvVisItem(val key: String, val x: Float, val y: Float, val w: Float, val h: Float)

private val bmpCache = WeakHashMap<android.graphics.drawable.Drawable, Bitmap>()
private fun bitmapOf(e: Entry): Bitmap? = e.bmp?.asAndroidBitmap() ?: synchronized(bmpCache) {
    bmpCache.getOrPut(e.icon) { runCatching { e.icon.toBitmap(128, 128) }.getOrNull() ?: return null }
}

/** Менеджеры раскладки с запретом прокрутки пальцем и колесом (столы без прокрутки: всё помещается). */
private class UGrid(c: Context, span: Int) : GridLayoutManager(c, span) {
    @JvmField var user = true
    override fun canScrollVertically() = user && super.canScrollVertically()
}
private class ULinear(c: Context) : LinearLayoutManager(c) {
    @JvmField var user = true
    override fun canScrollVertically() = user && super.canScrollVertically()
}

internal class MenuRvView(ctx: Context) : FrameLayout(ctx) {
    val rv: RecyclerView
    var items: List<Entry> = emptyList(); private set
    var params: MenuRvParams? = null; private set
    var st: MenuRvState = MenuRvState()
    var ops: MenuRvOps? = null
    private val adapter = Adapter()
    private var gridFocus = false
    private var longTile: TileView? = null
    private var dragStarted = false
    private var downX = 0f; private var downY = 0f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val loc = IntArray(2)
    private var decoKey: String? = null
    private var deco: RecyclerView.ItemDecoration? = null
    private var carry = 0f
    private var lastLayoutInfo = ""

    init {
        rv = RecyclerView(ctx)
        rv.itemAnimator = null
        rv.overScrollMode = OVER_SCROLL_NEVER
        rv.isFocusable = false
        rv.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rv.clipToPadding = false
        rv.clipChildren = false
        rv.setHasFixedSize(false)
        rv.setItemViewCacheSize(8)
        rv.recycledViewPool.setMaxRecycledViews(0, 80)
        adapter.setHasStableIds(true)
        rv.adapter = adapter
        addView(rv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        clipChildren = false
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) { reportScroll() }
        })
        rv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reportLayout() }
        rv.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(r: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragStarted = false }
                    MotionEvent.ACTION_MOVE -> if (longTile != null) {
                        if (!dragStarted && abs(e.x - downX) + abs(e.y - downY) > slop) beginDrag()
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (longTile != null) endTouchGesture() }
                }
                return false
            }
            override fun onTouchEvent(r: RecyclerView, e: MotionEvent) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> if (dragStarted) {
                        val t = longTile ?: return
                        val pkg = t.entry?.pkg ?: return
                        rv.getLocationInWindow(loc)
                        ops?.dragMove?.invoke(pkg, Offset(loc[0] + e.x, loc[1] + e.y))
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endTouchGesture()
                }
            }
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    private fun beginDrag() {
        val t = longTile ?: return
        val e = t.entry ?: return
        dragStarted = true
        t.dragFlag = true
        ops?.dragBegin?.invoke(e)
    }

    private fun endTouchGesture() {
        if (dragStarted) ops?.dragEnd?.invoke()
        longTile?.dragFlag = false
        longTile = null
        dragStarted = false
    }

    /** Долгое касание плитки: меню действий (с короткой задержкой, как раньше), а если после него потянули, значок переносится. */
    fun onTileLongClick(t: TileView) {
        val e = t.entry ?: return
        longTile = t; t.dragFlag = false; dragStarted = false
        postDelayed({ if (!t.dragFlag && longTile === t) ops?.long?.invoke(e) }, 170)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        super.onDetachedFromWindow()
    }

    private val focusListener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, _ ->
        val has = rv.hasFocus()
        if (has != gridFocus) { gridFocus = has; ops?.gridFocus?.invoke(has) }
    }

    /** Клавиши, пока в сетке есть фокус: сначала столы (страницы, цифры, края), потом сама плитка. */
    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (rv.hasFocus() && ops?.preKey?.invoke(ev) == true) return true
        return super.dispatchKeyEvent(ev)
    }

    // ---------- данные ----------
    private class Diff(val a: List<Entry>, val b: List<Entry>) : DiffUtil.Callback() {
        override fun getOldListSize() = a.size
        override fun getNewListSize() = b.size
        override fun areItemsTheSame(o: Int, n: Int) = a[o].pkg == b[n].pkg
        override fun areContentsTheSame(o: Int, n: Int): Boolean {
            val x = a[o]; val y = b[n]
            return x.label == y.label && x.bmp === y.bmp && x.icon === y.icon && x.updated == y.updated
        }
    }

    fun update(list: List<Entry>, p: MenuRvParams, state: MenuRvState, o: MenuRvOps) {
        st = state; ops = o
        val old = params
        params = p
        val lmNeeded = old == null || old.listMode != p.listMode || old.cols != p.cols
        if (lmNeeded) {
            val first = (rv.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: -1
            val lm: LinearLayoutManager = if (p.listMode) ULinear(context) else UGrid(context, p.cols.coerceAtLeast(1))
            rv.layoutManager = lm
            if (first > 0) rv.scrollToPosition(first)
        }
        if (lmNeeded || old == null || old.gapX != p.gapX || old.gapY != p.gapY || old.cols != p.cols) {
            deco?.let { rv.removeItemDecoration(it) }
            val cols = if (p.listMode) 1 else p.cols.coerceAtLeast(1)
            val gx = p.gapX; val gy = p.gapY
            val d = object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(out: android.graphics.Rect, v: View, parent: RecyclerView, s: RecyclerView.State) {
                    val pos = parent.getChildAdapterPosition(v)
                    if (pos < 0) return
                    val col = pos % cols
                    if (cols > 1) {
                        // границы ячеек GridLayoutManager и положения столбцов LazyVerticalGrid (остаток ширины отдаётся первым столбцам): вставки подгоняют одно к другому до пикселя
                        val total = parent.width - parent.paddingLeft - parent.paddingRight
                        val b = IntArray(cols + 1)
                        val base = total / cols; val rem0 = total % cols
                        var add = 0; var sum = 0
                        for (i in 1..cols) {
                            var sz = base; add += rem0
                            if (add > 0 && cols - add < rem0) { sz++; add -= cols }
                            sum += sz; b[i] = sum
                        }
                        val avail = total - gx * (cols - 1)
                        val slot = avail / cols; val rem = avail % cols
                        var x = 0
                        for (j in 0 until col) x += slot + (if (j < rem) 1 else 0) + gx
                        val w = slot + (if (col < rem) 1 else 0)
                        out.left = x - b[col]; out.right = b[col + 1] - (x + w)
                    }
                    out.top = if (pos < cols) 0 else gy
                }
            }
            deco = d; rv.addItemDecoration(d)
        }
        // плитка при «Увеличении при выделении» выходит за свои границы; без увеличения сетка обрезается по границам области, как у LazyVerticalGrid
        val zoomOn = p.focusZoom > 0
        rv.clipChildren = !zoomOn; clipChildren = !zoomOn
        (rv.layoutManager as? UGrid)?.user = p.userScroll
        (rv.layoutManager as? ULinear)?.user = p.userScroll
        rv.setPadding(p.padL, p.padT, p.padR, p.padB)
        val diff = DiffUtil.calculateDiff(Diff(items, list), false)
        items = list
        diff.dispatchUpdatesTo(adapter)
        if (old != null && old != p) adapter.notifyDataSetChanged()
        else refreshTiles()
    }

    fun refreshTiles() {
        for (i in 0 until rv.childCount) (rv.getChildAt(i) as? TileView)?.refreshState()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun getItemId(position: Int): Long = items[position].pkg.hashCode().toLong()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val t = TileView(parent.context, this@MenuRvView)
            t.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            return object : RecyclerView.ViewHolder(t) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as TileView).bind(items[position], params!!, this@MenuRvView)
        }
    }

    // ---------- положение, фокус, прокрутка ----------
    fun indexOf(pkg: String): Int { for (i in items.indices) if (items[i].pkg == pkg) return i; return -1 }

    fun tileAt(pos: Int): TileView? = rv.findViewHolderForAdapterPosition(pos)?.itemView as? TileView

    /** Ставит фокус на плитку; если её нет на экране, сначала прокручивает к ней. */
    fun focusPos(pos: Int, then: ((Boolean) -> Unit)? = null) {
        if (pos < 0 || pos >= items.size) { then?.invoke(false); return }
        val t = tileAt(pos)
        if (t != null && t.requestFocus()) { then?.invoke(true); return }
        if (t == null) rv.scrollToPosition(pos)
        // на слабой приставке раскладка после scrollToPosition занимает несколько кадров: пробуем снова (до 40 кадров)
        var left = 40
        val step = object : Runnable {
            override fun run() {
                val tt = tileAt(pos)
                if (tt != null && tt.requestFocus()) { then?.invoke(true); return }
                if (--left <= 0 || pos >= items.size) { then?.invoke(false); return }
                rv.postOnAnimation(this)
            }
        }
        rv.postOnAnimation(step)
    }

    fun focusPkg(pkg: String, then: ((Boolean) -> Unit)? = null) = focusPos(indexOf(pkg), then)

    val cols: Int get() = if (params?.listMode == true) 1 else (params?.cols ?: 1).coerceAtLeast(1)

    fun firstVisible(): Int = (rv.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
    fun firstOffset(): Int {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return 0
        val v = lm.findViewByPosition(firstVisible()) ?: return 0
        return -(v.top - rv.paddingTop).coerceAtMost(0)
    }

    fun scrollToItem(i: Int, off: Int = 0) { (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(i, -off) }

    fun isVisible(i: Int): Boolean {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return false
        return i in lm.findFirstVisibleItemPosition()..lm.findLastVisibleItemPosition() && lm.findLastVisibleItemPosition() >= 0
    }

    /** Показывает плитку целиком (минимальная прокрутка), как animateScrollBy у прежней сетки. */
    fun ensureVisible(index: Int) {
        val v = tileAt(index)
        if (v == null) { rv.smoothScrollToPosition(index); return }
        val top = v.top; val bot = v.bottom
        val vh = rv.height - rv.paddingBottom
        val d = if (top < rv.paddingTop) top - rv.paddingTop else if (bot > vh) bot - vh else 0
        if (d != 0) rv.smoothScrollBy(0, d)
    }

    /** Прокрутка в px (дробные накапливаются); возвращает, на сколько сдвинулось. */
    fun scrollByPx(d: Float): Float {
        carry += d
        val whole = carry.toInt()
        if (whole == 0) return 0f
        carry -= whole
        val before = rv.computeVerticalScrollOffset()
        rv.scrollBy(0, whole)
        return (rv.computeVerticalScrollOffset() - before).toFloat()
    }

    fun metrics(): Triple<Float, Float, Float>? {
        if (items.isEmpty()) return null
        val range = rv.computeVerticalScrollRange().toFloat()
        val ext = rv.computeVerticalScrollExtent().toFloat()
        if (range <= 0f) return null
        return Triple(rv.computeVerticalScrollOffset().toFloat(), ext, range)
    }

    fun visItems(): List<RvVisItem> {
        val out = ArrayList<RvVisItem>(rv.childCount)
        for (i in 0 until rv.childCount) {
            val c = rv.getChildAt(i) as? TileView ?: continue
            val e = c.entry ?: continue
            out.add(RvVisItem(e.pkg, c.left + rv.left.toFloat(), c.top + rv.top.toFloat(), c.width.toFloat(), c.height.toFloat()))
        }
        return out
    }

    val viewportHeight: Int get() = rv.height

    private fun reportScroll() {
        val i = firstVisible()
        ops?.scrolled?.invoke(i, firstOffset())
    }

    private fun reportLayout() {
        val p = params ?: return
        if (rv.childCount == 0) return
        val first = rv.getChildAt(0) ?: return
        var rows = 0
        var lastTop = -1
        for (i in 0 until rv.childCount) {
            val c = rv.getChildAt(i)
            if (c.top != lastTop && c.bottom <= rv.height - rv.paddingBottom) { rows++ }
            lastTop = c.top
        }
        val c = if (p.listMode) 1 else p.cols
        val key = "$c|$rows|${first.height}|${rv.height}"
        if (key == lastLayoutInfo) return
        lastLayoutInfo = key
        ops?.layoutInfo?.invoke(c, rows, first.height, rv.height)
    }

    /** Соседняя плитка в направлении кода клавиши или -1 (за краем). Геометрия как у поиска фокуса Compose по сетке. */
    fun neighbor(pos: Int, code: Int): Int {
        val n = items.size
        val cs = cols
        return when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (cs == 1 || pos % cs == 0) -1 else pos - 1
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (cs == 1 || pos % cs == cs - 1 || pos == n - 1) -1 else pos + 1
            KeyEvent.KEYCODE_DPAD_UP -> if (pos - cs >= 0) pos - cs else -1
            KeyEvent.KEYCODE_DPAD_DOWN -> { val t = pos + cs; if (t < n) t else if ((n - 1) / cs > pos / cs) n - 1 else -1 }
            else -> -1
        }
    }
}

/** Бегунок полосы прокрутки и перетаскивание ползунка работают поверх ScrollableState: тот же компонент, что у прежних списков. */
internal class RvScrollable(ctl: MenuRvCtl) : ScrollableState by ScrollableState({ d -> ctl.view?.scrollByPx(d); d })

internal class TileView(ctx: Context, private val host: MenuRvView) : View(ctx) {
    var entry: Entry? = null; private set
    var dragFlag = false
    private var p: MenuRvParams? = null
    private var bmp: Bitmap? = null
    private var hover = false
    private var okPending = false
    private var okDownAt = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private var textPaint: TextPaint? = null
    private var hidden = false

    init {
        isFocusable = true
        isClickable = true
        isLongClickable = true
        isFocusableInTouchMode = false
        setOnClickListener { val e = entry ?: return@setOnClickListener; host.ops?.sound?.invoke(this); host.ops?.click?.invoke(e) }
        setOnLongClickListener { host.onTileLongClick(this); true }
        // фон и рамка рисуются самой плиткой
        background = null
    }

    override fun hasOverlappingRendering() = false

    fun bind(e: Entry, params: MenuRvParams, h: MenuRvView) {
        entry = e; p = params
        bmp = bitmapOf(e)
        hidden = Cfg.isHidden(e.pkg)
        contentDescription = e.label
        okPending = false
        hover = false
        refreshState()
        requestLayout()
    }

    fun refreshState() {
        val e = entry ?: return
        val s = host.st
        val ghost = s.dragging && s.moving == e.pkg
        alpha = if (ghost) 0.28f else if (hidden) 0.5f else 1f
        invalidate()
    }

    private fun flagSel(): Boolean = entry?.pkg?.let { it in host.st.selected } == true
    private fun flagMov(): Boolean = entry?.pkg?.let { host.st.moving == it } == true
    private fun flagMerge(): Boolean = entry?.pkg?.let { host.st.mergeReady == it } == true
    private fun flagMarked(): Boolean = entry?.pkg?.let { host.st.marked == it } == true

    // ---------- размеры ----------
    // строка Text Material 24 sp: на приставке с надбавкой шрифта высота строки по снимкам равна 24 dp (без множителя шрифта)
    private fun lineH(pp: MenuRvParams) = 24f * pp.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val pp = p; val e = entry
        if (pp == null || e == null) { setMeasuredDimension(w, 0); return }
        val h: Float
        if (pp.listMode) {
            val textH = (if (pp.showLabels) lineH(pp) else 0f) + lineH(pp)
            h = 2 * pp.tilePad + maxOf(pp.listIcon, textH)
        } else {
            var c = 2 * pp.tilePad + pp.plate
            c += pp.labelGap
            val spec = pp.labelSpec
            if (pp.showLabels && spec != null) c += LabelCache.get(e.label, spec).heightPx
            h = c
        }
        setMeasuredDimension(w, Math.ceil(h.toDouble()).toInt())
    }

    // ---------- рисование ----------
    override fun onDraw(c: Canvas) {
        val pp = p ?: return
        val e = entry ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val d = pp.density
        val f = isFocused
        val sel = flagSel(); val mov = flagMov(); val merge = flagMerge(); val marked = flagMarked()
        val hv = hover
        val corner = 14f * d
        // подложка плитки
        val bg = if (sel) pp.cChipSel else if (f || hv || merge) pp.cTileFocus
            else if (pp.plates || pp.transparentTiles || (pp.dense && !pp.listMode)) 0 else pp.cTile
        if (bg != 0) { paint.style = Paint.Style.FILL; paint.color = bg; rect.set(0f, 0f, w, h); c.drawRoundRect(rect, corner, corner, paint) }
        if (pp.listMode) drawList(c, pp, e, w, h) else drawGrid(c, pp, e, w)
        // слой состояния волны Material (фокус и нажатие затемняют плитку на 10% цвета содержимого), как у прежней плитки с clickable
        if (f || isPressed) {
            paint.style = Paint.Style.FILL; paint.color = (pp.cOverlay and 0x00FFFFFF) or (0x1A shl 24)
            rect.set(0f, 0f, w, h); c.drawRoundRect(rect, corner, corner, paint)
        }
        // отметка выбора в углу плитки
        if (host.st.selectMode && !e.isFolder) {
            val sz = 24f * d; val pad = 6f * d
            val cx = w - pad - sz / 2f; val cy = pad + sz / 2f
            paint.style = Paint.Style.FILL; paint.color = if (sel) pp.cAccent else 0x66000000; c.drawCircle(cx, cy, sz / 2f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * d; paint.color = 0xFFFFFFFF.toInt(); c.drawCircle(cx, cy, sz / 2f - d, paint)
            if (sel) {
                paint.strokeWidth = 2f * d; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
                val k = 16f * d
                val ox = cx - k / 2f; val oy = cy - k / 2f
                val path = Path()
                path.moveTo(ox + k * 0.2f, oy + k * 0.55f); path.lineTo(ox + k * 0.42f, oy + k * 0.75f); path.lineTo(ox + k * 0.8f, oy + k * 0.3f)
                c.drawPath(path, paint)
                paint.strokeCap = Paint.Cap.BUTT
            }
        }
        // рамка: геометрия как у прежней плитки (штрих внутри границ, радиус 14 dp)
        if (merge || f || sel || mov || marked || hv) {
            val sw = (if (merge) 4f else if (f || sel || mov) 3f else if (marked) 2f else 1f) * d
            val col = if (merge || mov) 0xFFF59E0B.toInt() else if (sel) pp.cRing else if (f || hv) pp.cRing else (pp.cRing and 0x00FFFFFF) or (0x8C shl 24)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = sw; paint.color = col
            rect.set(sw / 2f, sw / 2f, w - sw / 2f, h - sw / 2f)
            c.drawRoundRect(rect, corner - sw / 2f, corner - sw / 2f, paint)
        }
    }

    private fun drawPlate(c: Canvas, pp: MenuRvParams, left: Float, top: Float, plate: Float) {
        val b = bmp
        if (!pp.plates) {
            val s = pp.iconOff.coerceAtMost(plate)
            if (b != null) { val o = Math.round((plate - s) / 2f).toFloat(); rect.set(left + o, top + o, left + o + s, top + o + s); c.drawBitmap(b, null, rect, bmpPaint) }
            return
        }
        val r = plate * 0.2f
        rect.set(left, top, left + plate, top + plate)
        if (!pp.transparentTiles) { paint.style = Paint.Style.FILL; paint.color = pp.cTile; c.drawRoundRect(rect, r, r, paint) }
        val bw = pp.density
        paint.style = Paint.Style.STROKE; paint.strokeWidth = bw; paint.color = pp.cBorder
        rect.set(left + bw / 2f, top + bw / 2f, left + plate - bw / 2f, top + plate - bw / 2f)
        c.drawRoundRect(rect, r - bw / 2f, r - bw / 2f, paint)
        val s = pp.iconOn.coerceAtMost(plate)
        if (b != null) { val o = Math.round((plate - s) / 2f).toFloat(); rect.set(left + o, top + o, left + o + s, top + o + s); c.drawBitmap(b, null, rect, bmpPaint) }
    }

    private fun drawGrid(c: Canvas, pp: MenuRvParams, e: Entry, w: Float) {
        val pad = pp.tilePad
        drawPlate(c, pp, pad + Math.round(((w - 2 * pad) - pp.plate) / 2f).toFloat(), pad, pp.plate)
        val spec = pp.labelSpec
        if (pp.showLabels && spec != null) {
            val ll = LabelCache.get(e.label, spec)
            val top = pad + pp.plate + pp.labelGap
            c.save()
            c.translate(pad + ((w - 2 * pad) - ll.widthPx) / 2f, top + ll.offsetY)   // как у прежней плитки: ((ширина области - ширина подписи) / 2, смещение)
            ll.layout.paint.color = pp.cText
            ll.layout.draw(c)
            c.restore()
        }
    }

    private fun drawList(c: Canvas, pp: MenuRvParams, e: Entry, w: Float, h: Float) {
        val pad = pp.tilePad
        val s = pp.listIcon
        drawPlate(c, pp, pad, (h - s) / 2f, s)
        val tp = textPaint ?: TextPaint(Paint.ANTI_ALIAS_FLAG).also { textPaint = it }
        val x = pad + s + 14f * pp.density
        val maxW = w - x - pad
        val lh = lineH(pp)
        val two = pp.showLabels
        val textH = if (two) lh * 2 else lh
        var y = (h - textH) / 2f
        // как у Text Material: шрифт по умолчанию, межбуквенный интервал 0,5 sp, строка 24 sp с пропорциональным распределением лишнего
        // строки Text Material: лишняя высота строки делится поровну (Alignment.Center), вторая строка поднята на 0,7 dp по снимкам X96
        fun line(t: String, sizePx: Float, color: Int, dy: Float) {
            tp.color = color; tp.textSize = sizePx; tp.typeface = Typeface.DEFAULT
            tp.letterSpacing = 0.5f * pp.fontScale * pp.density / sizePx
            val cut = android.text.TextUtils.ellipsize(t, tp, maxW, android.text.TextUtils.TruncateAt.END).toString()
            val fm = tp.fontMetrics
            val fh = fm.descent - fm.ascent
            val extra = lh - fh
            c.drawText(cut, x, y + extra / 2f - fm.ascent + dy * pp.density, tp)
            y += lh
        }
        if (two) line(e.label, pp.listLabelSp * pp.fontScale * pp.density, pp.cText, 0f)
        line(e.pkg, 11f * pp.fontScale * pp.density, pp.cHint, -0.7f)
    }

    // ---------- фокус, мышь, клавиши ----------
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val e = entry
        val pp = p
        if (gainFocus && e != null) host.ops?.focused?.invoke(e.pkg)
        if (pp != null && pp.focusZoom > 0 && !flagMerge()) {
            val z = if (gainFocus) 1f + pp.focusZoom / 100f else 1f
            scaleX = z; scaleY = z; translationZ = if (gainFocus) 1f else 0f
        }
        invalidate()
    }

    override fun onHoverEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> if (!hover) { hover = true; invalidate() }
            MotionEvent.ACTION_HOVER_EXIT -> if (hover) { hover = false; invalidate() }
        }
        return super.onHoverEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // правая кнопка мыши открывает те же действия, что долгое касание
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
            entry?.let { host.ops?.long?.invoke(it) }
            return true
        }
        return super.onTouchEvent(ev)
    }

    private fun menu() { entry?.let { host.ops?.long?.invoke(it) } }

    override fun onKeyDown(code: Int, ev: KeyEvent): Boolean {
        val e = entry ?: return super.onKeyDown(code, ev)
        val ops = host.ops ?: return super.onKeyDown(code, ev)
        val moving = flagMov()
        if (moving) {
            return when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { ops.moveKey(e.pkg, code); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> true
                else -> super.onKeyDown(code, ev)
            }
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (ev.repeatCount == 0) {
                    okPending = true
                    okDownAt = android.os.SystemClock.uptimeMillis()
                    // у части пультов при удержании нет повторов: долгое нажатие определяем по времени
                    postDelayed({ if (okPending) { okPending = false; OkGuard.arm(); menu() } }, LONG_OK_MS)
                } else if (okPending) { okPending = false; OkGuard.arm(); menu() }
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> { menu(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val pos = host.indexOf(e.pkg)
                val tgt = host.neighbor(pos, code)
                if (tgt >= 0) { host.focusPos(tgt); return true }
                return false
            }
        }
        return super.onKeyDown(code, ev)
    }

    override fun onKeyUp(code: Int, ev: KeyEvent): Boolean {
        val e = entry ?: return super.onKeyUp(code, ev)
        val ops = host.ops ?: return super.onKeyUp(code, ev)
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (flagMov()) { ops.click(e); return true }
                if (okPending) {
                    okPending = false
                    if (android.os.SystemClock.uptimeMillis() - okDownAt >= LONG_OK_MS) menu() else { ops.sound(this); ops.click(e) }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (flagMov()) return true
        }
        return super.onKeyUp(code, ev)
    }
}

/** Данные состояния одним снимком: читаются в своей области композиции (маленькая перекомпоновка), см. [MenuRvSync]. */
internal class MenuRvSnapshot(
    val selectMode: Boolean, val selected: Set<String>, val moving: String?, val mergeReady: String?, val dragging: Boolean, val marked: String?,
)

/** Перенос состояния Compose в сетку: тело экрана «Меню» эти состояния не читает, перекомпонуется только эта область. */
@Composable
internal fun MenuRvSync(ctl: MenuRvCtl, read: () -> MenuRvSnapshot) {
    val s = read()
    SideEffect {
        val st = ctl.state
        st.selectMode = s.selectMode; st.selected = s.selected; st.moving = s.moving; st.mergeReady = s.mergeReady; st.dragging = s.dragging; st.marked = s.marked
        ctl.refresh()
    }
}

/** Сетка в дереве Compose. */
@Composable
internal fun MenuRvGrid(items: List<Entry>, params: MenuRvParams, ops: MenuRvOps, ctl: MenuRvCtl, modifier: Modifier) {
    AndroidView(
        factory = { c -> MenuRvView(c).also { ctl.view = it } },
        modifier = modifier,
        update = { v -> ctl.view = v; v.update(items, params, ctl.state, ops) },
        onRelease = { v -> if (ctl.view === v) ctl.view = null },
    )
}
