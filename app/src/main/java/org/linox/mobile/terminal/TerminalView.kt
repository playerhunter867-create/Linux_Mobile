package org.linox.mobile.terminal

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Small dependency-free VT/ANSI terminal surface.
 * It intentionally implements the core subset needed by bash, nano, vim-like tools,
 * htop and tmux: printable UTF-8 text, CR/LF/BS/TAB, CSI cursor movement,
 * erase, SGR colors, alternate screen and scrollback.
 */
class TerminalView(context: Context) : View(context) {
    private var cols = 80
    private var rows = 24
    private var cellW = 0f
    private var cellH = 0f
    private var fontSize = 15f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val bg = Color.rgb(13,15,18)
    private val fg = Color.rgb(232,235,238)
    private var cx = 0
    private var cy = 0
    private var savedX = 0
    private var savedY = 0
    private var cursorVisible = true
    private var inverse = false
    private var fgColor = fg
    private var bgColor = bg
    private var bold = false
    private var state = State.NORMAL
    private val csi = StringBuilder()
    private val grid = ArrayList<CharCell>()
    private val altGrid = ArrayList<CharCell>()
    private var usingAlt = false
    private val scrollback = ArrayDeque<Array<CharCell>>()
    private val maxScrollback = 1200
    private var escPending = false

    data class CharCell(var ch: Char = ' ', var fg: Int = Color.rgb(232,235,238),
                        var bg: Int = Color.rgb(13,15,18), var bold: Boolean = false)

    enum class State { NORMAL, ESC, CSI }

    init {
        isFocusable = true
        setBackgroundColor(bg)
        rebuildGrid(80, 24)
    }

    private fun blank() = CharCell()
    private fun rebuildGrid(c: Int, r: Int) {
        cols = max(20,c); rows=max(5,r)
        grid.clear(); repeat(cols*rows){grid.add(blank())}
        altGrid.clear(); repeat(cols*rows){altGrid.add(blank())}
        cx=0;cy=0
        invalidate()
    }

    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        paint.textSize=fontSize
        val fm=paint.fontMetrics
        cellW=max(1f, paint.measureText("M"))
        cellH=max(1f, fm.descent-fm.ascent+2f)
        val nc=max(20,(w/cellW).toInt())
        val nr=max(5,(h/cellH).toInt())
        if(nc!=cols || nr!=rows) rebuildGrid(nc,nr)
    }

    fun dimensions(): Pair<Int,Int> = cols to rows

    fun feed(text:String) {
        text.forEach { consume(it) }
        postInvalidate()
    }

    private fun active():ArrayList<CharCell> = if(usingAlt) altGrid else grid
    private fun idx(x:Int,y:Int)=y*cols+x
    private fun put(ch:Char) {
        if(cx>=cols) { cx=0; newline() }
        active()[idx(cx,cy)] = CharCell(ch,fgColor,bgColor,bold)
        cx++
    }
    private fun newline() {
        cy++
        if(cy>=rows) {
            cy=rows-1
            if(!usingAlt) {
                val line=Array(cols){active()[idx(0,cy)]}.copyOf()
                scrollback.addLast(line)
                while(scrollback.size>maxScrollback) scrollback.removeFirst()
            }
            for(y in 1 until rows) for(x in 0 until cols) active()[idx(x,y-1)] = active()[idx(x,y)].copy()
            for(x in 0 until cols) active()[idx(x,rows-1)] = blank()
        }
    }
    private fun consume(ch:Char) {
        when(state) {
            State.NORMAL -> when(ch.code) {
                0x1b -> state=State.ESC
                10 -> newline()
                13 -> cx=0
                8 -> cx=max(0,cx-1)
                9 -> { val next=min(cols-1, ((cx/8)+1)*8); cx=next }
                7 -> {}
                in 32..126 -> put(ch)
                else -> if(ch.code>=0xA0) put(ch)
            }
            State.ESC -> when(ch) {
                '[' -> { csi.clear(); state=State.CSI }
                '7' -> {savedX=cx;savedY=cy;state=State.NORMAL}
                '8' -> {cx=savedX.coerceIn(0,cols-1);cy=savedY.coerceIn(0,rows-1);state=State.NORMAL}
                'c' -> {clear();state=State.NORMAL}
                'D' -> {newline();state=State.NORMAL}
                'M' -> {cy=max(0,cy-1);state=State.NORMAL}
                '=' -> state=State.NORMAL
                '>' -> state=State.NORMAL
                else -> state=State.NORMAL
            }
            State.CSI -> {
                if(ch in "@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz~") {
                    applyCsi(csi.toString(),ch); state=State.NORMAL
                } else csi.append(ch)
            }
        }
    }

    private fun nums(s:String):MutableList<Int> {
        val q=s.removePrefix("?").split(';')
        return q.map { it.filter{c->c.isDigit()}.toIntOrNull() ?: 0 }.toMutableList()
    }
    private fun applyCsi(raw:String, final:Char) {
        val privateMode=raw.startsWith("?")
        val n=nums(raw)
        val a={i:Int, d:Int-> if(i<n.size && n[i]!=0)n[i] else d}
        when(final) {
            'A'->cy=max(0,cy-a(0,1))
            'B'->cy=min(rows-1,cy+a(0,1))
            'C'->cx=min(cols-1,cx+a(0,1))
            'D'->cx=max(0,cx-a(0,1))
            'E'->{cy=min(rows-1,cy+a(0,1));cx=0}
            'F'->{cy=max(0,cy-a(0,1));cx=0}
            'G','`'->cx=(a(0,1)-1).coerceIn(0,cols-1)
            'd'->cy=(a(0,1)-1).coerceIn(0,rows-1)
            'H','f'->{cy=(a(0,1)-1).coerceIn(0,rows-1);cx=(a(1,1)-1).coerceIn(0,cols-1)}
            'J'->when(a(0,0)){0->eraseToEnd();1->eraseToStart();2,3->clear()}
            'K'->when(a(0,0)){0->eraseLineFromCursor();1->eraseLineToCursor();2->eraseLine()}
            'm'->sgr(n)
            's'->{savedX=cx;savedY=cy}
            'u'->{cx=savedX.coerceIn(0,cols-1);cy=savedY.coerceIn(0,rows-1)}
            'h','l'->if(privateMode && n.contains(1049)) {
                if(final=='h') switchAlt(true) else switchAlt(false)
            }
        }
    }
    private fun sgr(n:List<Int>) {
        if(n.isEmpty()) {resetStyle();return}
        n.forEach { v->
            when(v) {
                0->resetStyle();1->bold=true;7->inverse=true;22->bold=false;27->inverse=false
                30..37->fgColor=ansi(v-30,false);40..47->bgColor=ansi(v-40,false)
                39->fgColor=fg;49->bgColor=bg;90..97->fgColor=ansi(v-90,true)
                100..107->bgColor=ansi(v-100,true)
            }
        }
    }
    private fun ansi(i:Int, bright:Boolean):Int {
        val base=if(bright) arrayOf(0xFF777777.toInt(),0xFFFF5555.toInt(),0xFF55FF55.toInt(),0xFFFFFF55.toInt(),0xFF5555FF.toInt(),0xFFFF55FF.toInt(),0xFF55FFFF.toInt(),0xFFFFFFFF.toInt())
        else arrayOf(0xFF000000.toInt(),0xFFAA0000.toInt(),0xFF00AA00.toInt(),0xFFAA5500.toInt(),0xFF0000AA.toInt(),0xFFAA00AA.toInt(),0xFF00AAAA.toInt(),0xFFAAAAAA.toInt())
        return base[i.coerceIn(0,7)]
    }
    private fun resetStyle(){fgColor=fg;bgColor=bg;bold=false;inverse=false}
    private fun eraseLine(){for(x in 0 until cols)active()[idx(x,cy)]=blank()}
    private fun eraseLineFromCursor(){for(x in cx until cols)active()[idx(x,cy)]=blank()}
    private fun eraseLineToCursor(){for(x in 0..cx)active()[idx(x,cy)]=blank()}
    private fun eraseToEnd(){for(y in cy until rows)for(x in 0 until cols)if(y>cy||x>=cx)active()[idx(x,y)]=blank()}
    private fun eraseToStart(){for(y in 0..cy)for(x in 0 until cols)if(y<cy||x<=cx)active()[idx(x,y)]=blank()}
    private fun clear(){active().indices.forEach{active()[it]=blank()};cx=0;cy=0}
    private fun switchAlt(on:Boolean){
        if(on==usingAlt)return
        if(on){altGrid.indices.forEach{altGrid[it]=blank()};usingAlt=true}
        else usingAlt=false
        cx=0;cy=0
    }

    override fun onDraw(c:Canvas) {
        super.onDraw(c)
        c.drawColor(bg)
        val cells=active()
        for(y in 0 until rows) for(x in 0 until cols) {
            val cell=cells[idx(x,y)]
            val l=x*cellW; val top=y*cellH
            val inverseCell=cell.bg!=bg || inverse
            if(cell.bg!=bg) {paint.color=cell.bg;c.drawRect(l,top,l+cellW,top+cellH,paint)}
            if(cell.ch!=' ') {
                paint.color=if(inverseCell) cell.bg else cell.fg
                paint.textSize=fontSize
                paint.typeface=if(cell.bold)Typeface.MONOSPACE else Typeface.MONOSPACE
                c.drawText(cell.ch.toString(),l,top-cellHeightAscent(),paint)
            }
        }
        if(cursorVisible) {
            paint.color=fg
            c.drawRect(cx*cellW,cy*cellH,cx*cellW+2f,cy*cellH+cellH,paint)
        }
    }
    private fun cellHeightAscent():Float=paint.fontMetrics.ascent
    fun setFontSize(sp:Float){fontSize=sp;requestLayout();invalidate()}
    fun setCursorVisible(v:Boolean){cursorVisible=v;invalidate()}
}
