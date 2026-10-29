package com.maintiflow.pro.master

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.maintiflow.server.PdfBlock
import com.maintiflow.server.PdfDoc
import com.maintiflow.server.PdfPart
import com.maintiflow.server.PdfRenderer
import java.io.ByteArrayOutputStream

/**
 * Dessin de la fiche de panne avec android.graphics.pdf : A4, une page rose (déclaration) et une page jaune
 * (intervention et clôture) sous le même numéro. Unités : points PDF (1/72 pouce).
 */
class AndroidPdfRenderer : PdfRenderer {
    private companion object {
        const val W = 595
        const val H = 842
        const val MARGIN = 34f            // 12 mm
        const val TOP = 28f
        const val BOTTOM = H - 44f        // place du pied de page
        const val PAD = 3f
        val NAVY = Color.rgb(0x0B, 0x3D, 0x5C)
        val GREY = Color.rgb(0x5F, 0x6B, 0x73)
        val LIGHT = Color.rgb(0xEE, 0xF2, 0xF5)
        val BORDER = Color.rgb(0xB0, 0xBE, 0xC5)
    }

    private fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        this.color = color
    }

    private val base = paint(8.5f)
    private val bold = paint(8.5f, true)
    private val small = paint(7f, color = GREY)
    private val white = paint(9.5f, true, Color.WHITE)
    private val title = paint(14f, true, NAVY)
    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.5f; color = BORDER }

    private fun layout(text: String, p: TextPaint, width: Float, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) =
        StaticLayout.Builder.obtain(text, 0, text.length, p, maxOf(1, width.toInt())).setAlignment(align).build()

    private fun StaticLayout.drawAt(c: Canvas, x: Float, y: Float) {
        c.save(); c.translate(x, y); draw(c); c.restore()
    }

    /** Page en cours ; le pied de page et le filigrane sont dessinés à la fermeture de chaque page. */
    private inner class Writer(val doc: PdfDoc) {
        val pdf = PdfDocument()
        private var page: PdfDocument.Page? = null
        lateinit var canvas: Canvas
        var y = TOP
        private var number = 0
        val contentWidth = W - 2 * MARGIN
        /** Partie en cours : sa couleur de papier est reprise sur les pages suivantes. */
        var paper: Int? = null

        fun newPage() {
            finishPage()
            number++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(W, H, number).create()).also { canvas = it.canvas }
            paper?.let { canvas.drawColor(Color.rgb((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF)) }
            y = TOP
        }

        fun finishPage() {
            val p = page ?: return
            val c = p.canvas
            layout(doc.footer, paint(6.5f, color = GREY), contentWidth).drawAt(c, MARGIN, H - 34f)
            c.drawText("Page $number", W - MARGIN - small.measureText("Page $number"), H - 12f, small)
            doc.watermark?.let { wm ->
                val wp = paint(40f, true, Color.argb(31, 204, 26, 26)).apply { textAlign = Paint.Align.CENTER }
                c.save(); c.translate(W / 2f, H / 2f + 20f); c.rotate(-35f); c.drawText(wm, 0f, 0f, wp); c.restore()
            }
            pdf.finishPage(p)
            page = null
        }

        fun ensure(h: Float) {
            if (y + h > BOTTOM) newPage()
        }
    }

    override fun render(doc: PdfDoc): ByteArray {
        val w = Writer(doc)
        try {
            doc.parts.forEach { part ->
                w.paper = part.paper
                w.newPage()
                header(w, part)
                part.blocks.forEach { block(w, it) }
            }
            w.finishPage()
            return ByteArrayOutputStream().also { w.pdf.writeTo(it) }.toByteArray()
        } finally {
            w.pdf.close()
        }
    }

    private fun rgb(c: Int) = Color.rgb((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)

    private fun header(w: Writer, part: PdfPart) {
        val c = w.canvas
        val doc = w.doc
        c.drawText("MaintiFlow Pro", MARGIN, w.y + 10f, bold)
        layout(doc.company, small, 125f).drawAt(c, MARGIN, w.y + 13f)
        layout(doc.title, title, 250f, Layout.Alignment.ALIGN_CENTER).drawAt(c, MARGIN + 130f, w.y + 2f)
        val right = MARGIN + w.contentWidth - 140f
        c.drawText(doc.number, right, w.y + 12f, paint(12f, true, NAVY))
        layout(doc.statusLabel, small, 140f).drawAt(c, right, w.y + 16f)
        w.y += 40f
        fill.color = rgb(part.color)
        c.drawRect(MARGIN, w.y, MARGIN + w.contentWidth, w.y + 15f, fill)
        c.drawText(part.label, MARGIN + (w.contentWidth - white.measureText(part.label)) / 2, w.y + 11f, white)
        w.y += 23f
    }

    private fun block(w: Writer, b: PdfBlock) {
        when (b) {
            is PdfBlock.Section -> {
                w.ensure(40f)  // jamais un titre seul en bas de page
                fill.color = NAVY
                w.canvas.drawRect(MARGIN, w.y, MARGIN + w.contentWidth, w.y + 14f, fill)
                w.canvas.drawText(b.title, MARGIN + 4f, w.y + 10.5f, white)
                w.y += 14f
            }
            is PdfBlock.Grid -> {
                val total = b.widths.sum()
                val cols = b.widths.map { it / total * w.contentWidth }
                for (row in b.rows) {
                    val layouts = row.mapIndexed { i, cell ->
                        val p = when {
                            cell.small -> paint(7f)
                            cell.bold -> bold
                            else -> base
                        }
                        layout(cell.text, p, cols[i] - 2 * PAD)
                    }
                    val h = (layouts.maxOfOrNull { it.height } ?: 0) + 2 * PAD
                    w.ensure(h)
                    var x = MARGIN
                    row.forEachIndexed { i, cell ->
                        val r = RectF(x, w.y, x + cols[i], w.y + h)
                        if (cell.shaded) { fill.color = LIGHT; w.canvas.drawRect(r, fill) }
                        w.canvas.drawRect(r, stroke)
                        layouts[i].drawAt(w.canvas, x + PAD, w.y + PAD)
                        x += cols[i]
                    }
                    w.y += h
                }
                w.y += 6f
            }
            is PdfBlock.Signatures -> {
                val h = 96f
                w.ensure(h)
                val cw = w.contentWidth / b.cells.size
                b.cells.forEachIndexed { i, s ->
                    val x = MARGIN + i * cw
                    w.canvas.drawRect(RectF(x, w.y, x + cw, w.y + h), stroke)
                    var yy = w.y + PAD
                    layout(s.label, bold, cw - 2 * PAD).also { it.drawAt(w.canvas, x + PAD, yy); yy += it.height + 2 }
                    layout(s.lines.joinToString("\n"), base, cw - 2 * PAD).also { it.drawAt(w.canvas, x + PAD, yy); yy += it.height + 2 }
                    s.image?.let { img -> decode(img, 300)?.let { bmp -> drawFit(w.canvas, bmp, RectF(x + PAD, yy, x + PAD + 113f, yy + 40f)) } }
                    s.footer?.let { layout(it, small, cw - 2 * PAD).drawAt(w.canvas, x + PAD, w.y + h - 12f) }
                }
                w.y += h + 6f
            }
            is PdfBlock.Photos -> {
                val h = 104f
                w.ensure(h)
                val cw = w.contentWidth / 4
                b.photos.forEachIndexed { i, (bytes, label) ->
                    val x = MARGIN + i * cw
                    decode(bytes, 500)?.let { drawFit(w.canvas, it, RectF(x, w.y, x + cw - 8f, w.y + 90f)) }
                    w.canvas.drawText(label, x, w.y + h - 4f, small)
                }
                w.y += h + 6f
            }
            is PdfBlock.Note -> {
                val l = layout(b.text, small, w.contentWidth)
                w.ensure(l.height.toFloat())
                l.drawAt(w.canvas, MARGIN, w.y)
                w.y += l.height + 4f
            }
        }
    }

    /** Décode une image en limitant la mémoire (photos de 1600 px réduites pour le PDF). */
    private fun decode(bytes: ByteArray, maxPx: Int): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun drawFit(c: Canvas, bmp: Bitmap, box: RectF) {
        val scale = minOf(box.width() / bmp.width, box.height() / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        c.drawBitmap(bmp, null, RectF(box.left, box.top, box.left + dw, box.top + dh), Paint(Paint.FILTER_BITMAP_FLAG))
        bmp.recycle()
    }
}
