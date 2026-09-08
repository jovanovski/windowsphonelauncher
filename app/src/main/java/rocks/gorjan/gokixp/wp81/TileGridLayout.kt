package rocks.gorjan.gokixp.wp81

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * The Windows Phone 8.1 Start screen tile grid.
 *
 * WP8.1 lays tiles out on a fixed four-column grid of small cells. A medium tile is a
 * 2x2 block, a wide tile spans the whole row, and tiles are packed in order into the
 * first rectangle that fits - which is why a small tile will slide up to fill the gap
 * a medium one left beside it, rather than starting a new row.
 *
 * ### Metrics
 * Taken from the WVGA (480x800) reference layout, where a small tile is 99px, medium
 * 210, wide 432x210 and the gap 12. Those numbers are internally exact - `2*99 + 12 =
 * 210` and `4*99 + 3*12 = 432` - so they are re-expressed here as fractions of the
 * available width and scale to any screen:
 *
 * ```
 *   outer margin = 5dp, flat
 *   gap          = 2.5%  of width
 *   small cell   = (width - 2*5dp - 3*gap) / 4  ~= 22.5% of width
 * ```
 *
 * The reference layout also kept a 24px margin down each side - a twentieth of the
 * screen - and that is the one number here that is not honoured. A tile is a share of
 * the width, so that margin was never air around the wall: it was width taken off every
 * tile on the row. What is left is five flat pixels' worth at each end, enough that the
 * wall is not welded to the bezel and little enough that it reads as running to the
 * edges. It is the one metric here that does not scale, because a hairline is a
 * hairline on any screen. See [MARGIN_DP].
 */
class TileGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    /**
     * How many small cells fit across.
     *
     * Four is WP8.1's phone default and what every metric here is derived from. Three and
     * six are offered as well, for the reason the phone offered six: a cell is a share of
     * the width, so fewer of them is bigger tiles rather than a wider screen, and more of
     * them is a wall that holds more. The packer, the cell size and the resize drag all
     * read this, so changing it is the whole change.
     */
    var columns = COLUMNS
        set(value) {
            val next = value.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
            if (field == next) return
            field = next
            requestLayout()
        }

    /**
     * The width the margins and gaps are worked out from, or 0 for this view's own.
     *
     * Set by the wall to the phone's shorter side, so a tile and the air around it are the
     * same size whichever way up the phone is being held. See [columnsFor].
     */
    var metricBasis: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    private var cellPx = 0
    private var gapPx = 0
    private var marginPx = 0

    /**
     * Empty height kept under the last row, on top of what the tiles need.
     *
     * A selected tile's resize handle is centred on its bottom-right corner and so reaches
     * below the row it is on. On the last row that is below the grid itself, and a parent
     * clips a child to the child's own bounds - so the wall grows by the overhang rather
     * than the handle being cut in half by the end of it.
     *
     * Placement ignores this: it is height the grid has, not height it lays anything out
     * in, so nothing moves and the room only shows once the wall is scrolled to the end.
     */
    var bottomReservePx = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /**
     * Empty height kept above the first row, which the rows are pushed down by.
     *
     * The same problem as [bottomReservePx] at the other end - the selected tile's unpin
     * handle is centred on its top-right corner and so reaches above the row it is on -
     * and it cannot be solved the same way. Height added under the last row is room the
     * wall can grow into; there is no growing upwards, because the first row starts at this
     * layout's own top edge and above that is the scroller's, which clips. So this one
     * moves the tiles instead: every row is placed [topReservePx] lower, and the air that
     * leaves at the top of the wall is where the handle goes.
     *
     * It is air the phone had anyway. Tiles butted up against the status bar is not what
     * Start looked like.
     */
    var topReservePx = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** Resolved top-left cell of each child, parallel to child index. */
    private val placements = mutableListOf<Placement>()

    data class Placement(val col: Int, val row: Int, val cols: Int, val rows: Int)

    // ---------------------------------------------------------------- inline expansion

    /**
     * A folder opened in place, and the room made for it.
     *
     * Windows Phone's Live Folders did not push a page: the wall parted under the folder
     * and its contents appeared in the gap. That is what this is - one full-width child
     * laid out in a band between two rows, with everything below it moved down by exactly
     * the band's height.
     *
     * The band is not packed with the tiles. It is a child of this layout so that it
     * scrolls and clips with the wall, but the packer steps over it: it occupies no cells,
     * it occupies a gap between rows.
     */
    var bandView: View? = null
        private set

    /** The first row that has to move down to make the gap. */
    private var bandRow = Int.MAX_VALUE

    /** The tile the band hangs under, by id. Resolved to a row on every pack. */
    private var bandUnderTag: Any? = null

    /**
     * How much of the band's height is currently being given, 0 to 1.
     *
     * The opening slide is a change of height rather than a translation, because the wall
     * below has to move with it: the tiles are being pushed down, not slid over.
     */
    var bandProgress: Float = 1f
        set(value) {
            val next = value.coerceIn(0f, 1f)
            if (field == next) return
            field = next
            requestLayout()
        }

    var bandFullHeight = 0
        private set

    private val bandHeight: Int get() = (bandFullHeight * bandProgress).toInt()

    /**
     * Whether the band is held to its own bounds while it is drawn.
     *
     * The band cannot do this for itself. This layout does not clip its children - an edit
     * handle has to be able to hang off a tile - and a child that is not clipped by its
     * parent is drawn whole however tall the box it was laid out in: the folder's tiles
     * were being painted straight over the wall below while the gap was still opening.
     *
     * So the clip is applied from here, and only while the gap is moving. Once it is fully
     * open the tiles inside it are ordinary tiles again and their handles need the same
     * freedom as everything else's.
     */
    var bandClipped = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun drawChild(
        canvas: android.graphics.Canvas,
        child: View,
        drawingTime: Long
    ): Boolean {
        if (child !== bandView || !bandClipped) return super.drawChild(canvas, child, drawingTime)
        val saved = canvas.save()
        canvas.clipRect(child.left, child.top, child.right, child.bottom)
        val drawn = super.drawChild(canvas, child, drawingTime)
        canvas.restoreToCount(saved)
        return drawn
    }

    /**
     * Puts [view] in the wall under whichever tile carries [underTag], or takes it away.
     */
    /**
     * How many of the children are tiles.
     *
     * The band is a child of this layout as well, and it is not a tile. Anything that
     * treats a child index as a position in the wall's own list of tiles has to stop short
     * of it, or the list is asked for an entry one past its end - which is what a drag out
     * of a folder was doing.
     */
    val tileCount: Int get() = childCount - (if (bandView != null) 1 else 0)

    /** Adds a tile, keeping it ahead of the band so child order matches tile order. */
    fun addTile(view: View) {
        addView(view, tileCount)
    }

    fun setBand(view: View?, underTag: Any?) {
        bandView?.let { if (it !== view) removeView(it) }
        bandView = view
        bandUnderTag = underTag
        if (view != null && view.parent == null) addView(view)
        requestLayout()
    }

    /**
     * The side of one small cell, in pixels.
     *
     * Read by the tiles: what a widget sets its reading at is worked out from a single
     * cell rather than from the tile it happens to be on, so every reading on the wall
     * comes out the same size. See TileView.sizeAsNumber.
     */
    val cellSize: Int
        get() = cellPx

    /** Pixel bounds of a placement, relative to this view. */
    fun boundsOf(p: Placement): android.graphics.Rect {
        val left = marginPx + p.col * (cellPx + gapPx)
        val top = topReservePx + p.row * (cellPx + gapPx) +
            if (p.row >= bandRow) bandHeight else 0
        return android.graphics.Rect(
            left, top,
            left + spanPx(p.cols), top + spanPx(p.rows)
        )
    }

    fun placementAt(index: Int): Placement? = placements.getOrNull(index)

    // ---------------------------------------------------------------- handle overhang

    /**
     * The tile whose edit handle the current gesture began on, if it began outside it.
     *
     * A handle is centred on its tile's corner, so half of it hangs into this grid rather
     * than sitting on the tile. A parent only offers a child the touches that land inside
     * that child, so the outer half would be dead: the tile never hears about it, and
     * whatever is behind it does.
     *
     * The grid is the view that *does* hear about it, so the grid hands it on. Only the
     * two handle rectangles are claimed - not the whole overhang - so a tap that merely
     * strays near a selected tile still belongs to whatever it actually landed on.
     */
    private var handleTouchTarget: TileView? = null

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            handleTouchTarget = handleOwnerAt(ev.x, ev.y)
        }
        val target = handleTouchTarget ?: return super.dispatchTouchEvent(ev)

        // Handed on in the tile's own coordinates. The selected tile is neither scaled nor
        // translated - the wall around it is what stands back, and a tile being dragged is
        // claimed by the scroller long before this - so the offset is the whole transform.
        val local = android.view.MotionEvent.obtain(ev)
        local.offsetLocation(-target.left.toFloat(), -target.top.toFloat())
        val handled = try {
            target.dispatchTouchEvent(local)
        } finally {
            local.recycle()
        }
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> handleTouchTarget = null
        }
        return handled
    }

    /** The selected tile, if [x],[y] falls on one of its handles rather than on it. */
    private fun handleOwnerAt(x: Float, y: Float): TileView? {
        for (i in childCount - 1 downTo 0) {
            val tile = getChildAt(i) as? TileView ?: continue
            if (!tile.isEditMode) continue
            // Only one tile is ever selected, so there is nothing else to look at.
            val local = tile.handleHit(x - tile.left, y - tile.top)
            return if (local) tile else null
        }
        return null
    }

    private fun spanPx(span: Int) = span * cellPx + (span - 1) * gapPx

    private fun tileSizeOf(child: View): TileSize =
        (child as? TileView)?.tile?.size ?: TileSize.MEDIUM

    // ---------------------------------------------------------------- the packer

    /**
     * Occupancy, kept between packings rather than made for each.
     *
     * A drag packs the whole wall out once per candidate index on every move it makes -
     * see [dropIndexFor] - and a fresh row of booleans for each of those is a great deal of
     * rubbish for an answer that is thrown away a frame later.
     */
    private val occupancy = ArrayList<BooleanArray>()

    /** How many rows [packSpans] actually reached, which is the wall's height in rows. */
    private var occupiedRows = 0

    /** Footprints and results, parallel to child index. Grown on demand, never shrunk. */
    private var spanW = IntArray(0)
    private var spanH = IntArray(0)
    private var packedCol = IntArray(0)
    private var packedRow = IntArray(0)
    private var tryW = IntArray(0)
    private var tryH = IntArray(0)

    private fun ensureScratch(n: Int) {
        if (spanW.size >= n) return
        spanW = IntArray(n)
        spanH = IntArray(n)
        packedCol = IntArray(n)
        packedRow = IntArray(n)
        tryW = IntArray(n)
        tryH = IntArray(n)
    }

    /**
     * Places [count] footprints in order, first-fit, writing where each landed into
     * [packedCol] and [packedRow]. Returns the number of rows used.
     *
     * The packer proper, and the only copy of it. [pack] runs it over the children as they
     * stand; a drag runs it over an order it is merely *thinking* of trying. A hypothetical
     * has to be packed by exactly the same rules as the real thing, or the wall a drag
     * shows is not the wall it hands over.
     *
     * A footprint of no cells - a hidden child, the band - is stepped over and left at the
     * origin, which keeps the answers parallel to the questions.
     *
     * The scan starts from the row the *previous* footprint landed on rather than from the
     * top. That keeps the two behaviours that matter and which pull against each other:
     *
     *  - a small tile can still backfill a gap beside the tile before it, which is what
     *    lets a row of small tiles sit next to a medium one;
     *  - but it can never jump back above tiles that precede it in the order.
     *
     * Scanning from row 0 every time broke the second: a small tile dragged down the grid
     * would find the hole it had just vacated near the top and snap straight back into it,
     * so small tiles appeared to be stuck in the first row.
     */
    private fun packSpans(widths: IntArray, heights: IntArray, count: Int): Int {
        // A wall that has been set to a different number of columns has rows of the wrong
        // length lying about in it.
        if (occupancy.isNotEmpty() && occupancy[0].size != columns) occupancy.clear()
        for (row in occupancy) java.util.Arrays.fill(row, false)
        occupiedRows = 0

        fun rowAt(r: Int): BooleanArray {
            while (occupancy.size <= r) occupancy.add(BooleanArray(columns))
            if (r >= occupiedRows) occupiedRows = r + 1
            return occupancy[r]
        }

        fun fits(col: Int, row: Int, w: Int, h: Int): Boolean {
            if (col + w > columns) return false
            for (r in row until row + h) {
                val cells = rowAt(r)
                for (c in col until col + w) if (cells[c]) return false
            }
            return true
        }

        fun occupy(col: Int, row: Int, w: Int, h: Int) {
            for (r in row until row + h) {
                val cells = rowAt(r)
                for (c in col until col + w) cells[c] = true
            }
        }

        // The earliest row any subsequent footprint may occupy. Never moves backwards.
        var frontier = 0

        for (i in 0 until count) {
            val w = widths[i]
            val h = heights[i]
            if (w <= 0 || h <= 0) {
                packedCol[i] = 0
                packedRow[i] = 0
                continue
            }
            var placed = false
            var row = frontier
            while (!placed) {
                for (col in 0..(columns - w)) {
                    if (fits(col, row, w, h)) {
                        occupy(col, row, w, h)
                        packedCol[i] = col
                        packedRow[i] = row
                        // Left at this tile's own row, not the row after it, so the next
                        // tile can still share the band beside it.
                        frontier = row
                        placed = true
                        break
                    }
                }
                if (!placed) row++
            }
        }
        return occupiedRows
    }

    /** Reads each child's footprint into [spanW]/[spanH]. Zero for anything not packed. */
    private fun readSpans(count: Int) {
        ensureScratch(count.coerceAtLeast(1))
        for (i in 0 until count) {
            val child = getChildAt(i)
            // The band lives between rows rather than in them, so the packer steps over it
            // exactly as it steps over a hidden tile.
            if (child.visibility == GONE || child === bandView) {
                spanW[i] = 0
                spanH[i] = 0
            } else {
                val size = tileSizeOf(child)
                spanW[i] = size.cols.coerceAtMost(columns)
                spanH[i] = size.rows
            }
        }
    }

    /** Packs the children as they stand into [placements]. Returns the rows used. */
    private fun pack(): Int {
        val n = childCount
        readSpans(n)
        val rows = packSpans(spanW, spanH, n)
        placements.clear()
        for (i in 0 until n) {
            placements.add(Placement(packedCol[i], packedRow[i], spanW[i], spanH[i]))
        }
        return rows
    }

    // ---------------------------------------------------------------- where a drop goes

    private fun centreX(col: Int, cols: Int): Float =
        marginPx + col * (cellPx + gapPx) + spanPx(cols) / 2f

    private fun centreY(row: Int, rows: Int): Float =
        topReservePx + row * (cellPx + gapPx) +
            (if (row >= bandRow) bandHeight else 0) + spanPx(rows) / 2f

    /**
     * The index [child] has to move to for it to land where [x],[y] is asking, or -1 for
     * "leave it where it is".
     *
     * Answered by trying it. Every index the tile could be moved to is packed out in full,
     * and the one that puts the tile nearest the point wins - so the wall a drop makes is
     * the wall the drag was already showing, and a gap can be dropped into whenever any
     * order at all reaches it.
     *
     * It has to be this way round, because a tile's place on the wall is not stored, it is
     * *derived*: a tile has an index, and where it sits is whatever the packer makes of the
     * whole order. Reading the answer off the geometry instead - which slot the point falls
     * between, in reading order - gets the ordinary cases right and then quietly misses the
     * ones that matter, because the index reading order names need not be the index that
     * lands the tile there. The empty space beside a tall tile is exactly such a case: the
     * tile went somewhere else, or nowhere, and the space could not be dropped into at all.
     *
     * [x] and [y] are this layout's own coordinates, and want to be the middle of the tile
     * being dragged rather than the finger - it is the tile that is being put somewhere.
     * See StartScreenView.dragProbe.
     *
     * Costs a packing of the wall per tile on it, so it is asked once a drag has settled on
     * somewhere rather than on every move - see StartScreenView.reorderUnder and
     * [probeCell].
     */
    fun dropIndexFor(child: View, x: Float, y: Float): Int {
        val n = tileCount
        if (n < 2 || cellPx <= 0) return -1
        readSpans(n)

        var from = -1
        for (i in 0 until n) if (getChildAt(i) === child) { from = i; break }
        if (from < 0) return -1

        var best = from
        var bestScore = Float.MAX_VALUE
        for (to in 0 until n) {
            for (i in 0 until n) {
                // Where the i-th footprint of the reordered wall comes from: the tile
                // itself at its new index, and everything else closing up behind the hole
                // it left.
                val src = when {
                    i == to -> from
                    i < to -> if (i < from) i else i + 1
                    else -> if (i - 1 < from) i - 1 else i
                }
                tryW[i] = spanW[src]
                tryH[i] = spanH[src]
            }
            packSpans(tryW, tryH, n)
            val dx = centreX(packedCol[to], tryW[to]) - x
            val dy = centreY(packedRow[to], tryH[to]) - y
            val score = dx * dx + dy * dy
            // Ties go to the smallest move. Whole runs of indices pack to the very same
            // wall - a tile put before or after one that ends up on another row - and
            // choosing between those by anything other than "leave it alone" is a wall
            // that shuffles under a hand holding still.
            val nearer = score < bestScore - TIE
            val level = score <= bestScore + TIE &&
                kotlin.math.abs(to - from) < kotlin.math.abs(best - from)
            if (nearer || level) {
                bestScore = kotlin.math.min(score, bestScore)
                best = to
            }
        }
        return if (best == from) -1 else best
    }

    /**
     * A cheap name for the part of the wall a point is on, or [NO_CELL].
     *
     * Nothing is looked up with it: it exists so a drag can tell whether it is still asking
     * the same question as last time, because the answer - [dropIndexFor] - costs a packing
     * of the wall per tile and is not worth asking twice over.
     */
    fun probeCell(x: Float, y: Float): Int {
        if (cellPx <= 0) return NO_CELL
        val pitch = cellPx + gapPx
        val col = Math.floorDiv((x - marginPx).toInt(), pitch).coerceIn(-1, MAX_COLUMNS)
        val row = Math.floorDiv((y - topReservePx).toInt(), pitch)
        return row * (MAX_COLUMNS + 2) + col
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)

        // Measured against the phone's shorter side rather than against this wall's width.
        // The gap is a share of the screen, and on a screen held sideways - twice as wide
        // and no taller - a share of the width is a chasm: the air between tiles would
        // double while the tiles themselves stayed the same size. The outer margin is flat
        // and so has no such trouble.
        val basis = if (metricBasis > 0) metricBasis else width
        gapPx = (basis * GAP_FRACTION).toInt()
        val outer = marginPxFor(resources.displayMetrics.density)
        cellPx = ((width - 2 * outer - (columns - 1) * gapPx) / columns.toFloat()).toInt()
        // What the row does not use, split between the two ends: the margin asked for,
        // plus whatever would otherwise be left over. A cell is a whole number of pixels
        // and a row of them rarely divides the screen exactly, so there is always a little
        // spare - and left where it fell, all of it piled up on the right and the wall sat
        // a pixel or two off centre.
        marginPx = (width - (columns * cellPx + (columns - 1) * gapPx)) / 2

        val rows = pack()
        resolveBandRow()

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE || child === bandView) continue
            val p = placements[i]
            // A tile on the last column has the screen's edge for a right-hand neighbour,
            // and its handles are centred on that edge. Told here rather than worked out
            // by the tile, because which column a tile is on is the grid's business and
            // it has just this moment decided it. See TileView.tuckHandles.
            (child as? TileView)?.tuckHandles(p.col + p.cols >= columns)
            child.measure(
                MeasureSpec.makeMeasureSpec(spanPx(p.cols), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(spanPx(p.rows), MeasureSpec.EXACTLY)
            )
        }

        // Measured at its natural height and then given whatever share of it the slide has
        // reached: the band knows how tall it wants to be, and the animation only decides
        // how much of that the wall has made room for so far.
        bandView?.let { band ->
            band.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            bandFullHeight = band.measuredHeight
        } ?: run { bandFullHeight = 0 }

        val height = if (rows == 0) 0 else rows * cellPx + (rows - 1) * gapPx
        setMeasuredDimension(width, topReservePx + height + bandHeight + bottomReservePx)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE || child === bandView) continue
            val bounds = boundsOf(placements[i])
            child.layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
        // Laid out at its full height in a box that is only as tall as the slide has
        // opened. The band clips its own children, so what has not been made room for yet
        // is simply not drawn - which is what makes the contents appear to come down out
        // of the tile rather than to be squashed into the gap.
        bandView?.let { band ->
            val top = topReservePx + bandRow * (cellPx + gapPx)
            band.layout(0, top, width, top + bandHeight)
        }
    }

    /**
     * Finds the row the band sits above: the one after the tile it belongs to.
     *
     * Re-derived on every measure rather than remembered, because the tile it hangs under
     * moves - a resize above it, a reorder, a change of column count - and a band pinned to
     * a stale row would open in the middle of somebody else's row.
     */
    private fun resolveBandRow() {
        bandRow = Int.MAX_VALUE
        val tag = bandUnderTag ?: return
        var row = -1
        for (i in 0 until childCount) {
            val child = getChildAt(i) as? TileView ?: continue
            if (child.tile.id != tag) continue
            row = (placements.getOrNull(i) ?: return).let { it.row + it.rows }
            break
        }
        if (row < 0) return

        // The gap has to fall on a boundary no tile crosses. A 1x1 folder on the top row
        // next to a 2x2 ends one row down, and that row is the middle of its neighbour -
        // opening there put the folder straight through the tile beside it, because a tile
        // is only pushed down when it *starts* below the gap.
        //
        // So the boundary is walked down past anything straddling it, repeatedly: pushing
        // past one tile can land in the middle of a taller one.
        var moved = true
        while (moved) {
            moved = false
            for (i in 0 until childCount) {
                if (getChildAt(i) === bandView) continue
                val p = placements.getOrNull(i) ?: continue
                if (p.rows == 0) continue
                if (p.row < row && p.row + p.rows > row) {
                    row = p.row + p.rows
                    moved = true
                }
            }
        }
        bandRow = row
    }

    /**
     * How many grid columns a pixel width spans, rounded to the nearest whole column.
     * Used while dragging the resize handle to turn a width into a tile size.
     */
    fun columnsForWidth(px: Float): Int {
        if (cellPx <= 0) return 1
        val span = ((px + gapPx) / (cellPx + gapPx)).let { Math.round(it) }
        return span.coerceIn(1, columns)
    }

    /**
     * How many rows a pixel height spans, rounded to the nearest whole row.
     *
     * Needed alongside [columnsForWidth] because width alone does not identify a size:
     * the banner and the medium tile are both two cells across and differ only in height.
     * The wall has a width to clamp against and no height, so the ceiling here is the one
     * a tile carries with it - see [TileSize.MAX_ROWS].
     */
    fun rowsForHeight(px: Float): Int {
        if (cellPx <= 0) return 1
        val span = ((px + gapPx) / (cellPx + gapPx)).let { Math.round(it) }
        return span.coerceIn(1, TileSize.MAX_ROWS)
    }

    /**
     * The child index of the tile the given point is on, or -1 if it is on none.
     *
     * A tile and only a tile: the gutters, the empty end of a row and the holes the packer
     * leaves all answer -1, because there is nothing there to be on. Asking where a tile
     * *belongs* is a different question and a question every point has an answer to - see
     * [dropIndexFor].
     */
    fun indexAt(x: Float, y: Float): Int {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE || child === bandView) continue
            if (boundsOf(placements[i]).contains(x.toInt(), y.toInt())) return i
        }
        return -1
    }

    companion object {
        /** No part of the wall: what [probeCell] answers before there is a wall. */
        const val NO_CELL = Int.MIN_VALUE

        /**
         * How close two placements have to score to count as the same, in square pixels.
         *
         * Two candidate indices that pack to identical walls score identically, so this is
         * only guarding the float arithmetic that got them there.
         */
        private const val TIE = 1f

        /**
         * How many columns of the usual size fit across [available].
         *
         * [basis] is the phone's shorter side and [portraitColumns] what the user set the
         * wall to across it - so the cell that pair implies is worked out first, and then
         * as many of them as the width in hand will take. Given the phone upright the two
         * are the same number and this returns the setting untouched; laid on its side it
         * returns however many more fit, which is what keeps a tile the size it is either
         * way instead of stretching four of them across a screen twice as wide.
         *
         * [density] only to turn the flat outer margin into pixels - see [MARGIN_DP].
         */
        fun columnsFor(available: Int, basis: Int, portraitColumns: Int, density: Float): Int {
            if (available <= 0 || basis <= 0) return portraitColumns
            val margin = marginPxFor(density).toFloat()
            val gap = basis * GAP_FRACTION
            val cell = (basis - 2 * margin - (portraitColumns - 1) * gap) / portraitColumns
            if (cell <= 0f) return portraitColumns
            return Math.round((available - 2 * margin + gap) / (cell + gap))
                .coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        }

        const val COLUMNS = 4

        /**
         * Air down each side of the wall.
         *
         * A hairline, and flat rather than a share of the screen. The phone kept a
         * twentieth of the width at each edge, which on a screen this size is a tenth of
         * the row spent on nothing - and spent out of the tiles, since a cell is what is
         * left of the row once the margins and the gaps have been taken. So the wall runs
         * to the edges, with just enough held back that the tiles are not welded to the
         * bezel.
         *
         * Read by more than the grid: a folder's heading has to start where the tiles
         * start, so it asks for the same number. See [marginPxFor].
         */
        const val MARGIN_DP = 5f

        /** [MARGIN_DP] in pixels, for whichever screen is asking. */
        fun marginPxFor(density: Float): Int = (MARGIN_DP * density).toInt()
        private const val GAP_FRACTION = 0.025f

        // What the wall can be set to. Two would make a medium tile the whole width; the
        // ceiling is for the screen on its side, where the count is the portrait one
        // scaled by how much wider the screen has become - see StartScreenView.applyColumns.
        private const val MIN_COLUMNS = 3
        private const val MAX_COLUMNS = 12
    }
}
