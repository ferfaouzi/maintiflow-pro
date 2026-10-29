package com.maintiflow.pc

import com.maintiflow.server.PdfBlock
import com.maintiflow.server.PdfDoc
import com.maintiflow.server.PdfPart
import com.maintiflow.server.PdfRenderer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.apache.pdfbox.util.Matrix
import java.awt.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Dessin de la fiche de panne avec PDFBox, même mise en page que le téléphone maître (AndroidPdfRenderer) :
 * A4, page rose (déclaration) puis page jaune (intervention et clôture) sous le même numéro.
 * Police DejaVu Sans embarquée (accents, symboles). Coordonnées : y mesuré depuis le haut, converti au dessin.
 */
class PdfBoxRenderer : PdfRenderer {
    private companion object {
        const val W = 595f
        const val H = 842f
        const val MARGIN = 34f
        const val TOP = 28f
        const val BOTTOM = H - 44f
        const val PAD = 3f
        val NAVY = Color(0x0B, 0x3D, 0x5C)
        val GREY = Color(0x5F, 0x6B, 0x73)
        val LIGHT = Color(0xEE, 0xF2, 0xF5)
        val BORDER = Color(0xB0, 0xBE, 0xC5)
    }

    override fun render(doc: PdfDoc): ByteArray = PDDocument().use { pd ->
        val regular = loadFont(pd, "/fonts/DejaVuSans.ttf")
        val bold = loadFont(pd, "/fonts/DejaVuSans-Bold.ttf")
        Writer(pd, doc, regular, bold).run {
            doc.parts.forEach { part ->
                paper = part.paper
                newPage()
                header(part)
                part.blocks.forEach { block(it) }
            }
            finishPage()
        }
        ByteArrayOutputStream().also { pd.save(it) }.toByteArray()
    }

    private fun loadFont(pd: PDDocument, path: String): PDFont =
        PdfBoxRenderer::class.java.getResourceAsStream(path)!!.use { PDType0Font.load(pd, it) }

    private inner class Writer(val pd: PDDocument, val doc: PdfDoc, val regular: PDFont, val bold: PDFont) {
        lateinit var cs: PDPageContentStream
        private var open = false
        var y = TOP
        var paper: Int? = null
        private var number = 0
        val contentWidth = W - 2 * MARGIN

        fun newPage() {
            finishPage()
            number++
            val page = PDPage(PDRectangle.A4)
            pd.addPage(page)
            cs = PDPageContentStream(pd, page)
            open = true
            paper?.let { rect(0f, 0f, W, H, rgb(it)) }
            y = TOP
        }

        fun finishPage() {
            if (!open) return
            paragraph(doc.footer, regular, 6.5f, contentWidth).let { lines -> drawLines(lines, regular, 6.5f, MARGIN, H - 34f, GREY) }
            text("Page $number", regular, 7f, W - MARGIN - width("Page $number", regular, 7f), H - 12f, GREY)
            doc.watermark?.let { wm ->
                cs.saveGraphicsState()
                cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.12f })
                cs.beginText()
                cs.setFont(bold, 40f)
                cs.setNonStrokingColor(Color(204, 26, 26))
                val w = width(wm, bold, 40f)
                cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(35.0), W / 2 - w / 2 * 0.82f, H / 2 - 20f - w / 2 * 0.57f))
                cs.showText(safe(wm, bold))
                cs.endText()
                cs.restoreGraphicsState()
            }
            cs.close()
            open = false
        }

        fun ensure(h: Float) { if (y + h > BOTTOM) newPage() }

        // ---------------------------------------------------------------- primitives (y depuis le haut)
        fun rect(x: Float, top: Float, w: Float, h: Float, fill: Color? = null, stroke: Color? = null) {
            cs.addRect(x, H - top - h, w, h)
            when {
                fill != null && stroke != null -> { cs.setNonStrokingColor(fill); cs.setStrokingColor(stroke); cs.setLineWidth(0.5f); cs.fillAndStroke() }
                fill != null -> { cs.setNonStrokingColor(fill); cs.fill() }
                else -> { cs.setStrokingColor(stroke ?: BORDER); cs.setLineWidth(0.5f); cs.stroke() }
            }
        }

        /** Texte posé sur sa ligne de base [baseline] (depuis le haut). */
        fun text(s: String, font: PDFont, size: Float, x: Float, baseline: Float, color: Color = Color.BLACK) {
            cs.beginText()
            cs.setFont(font, size)
            cs.setNonStrokingColor(color)
            cs.newLineAtOffset(x, H - baseline)
            cs.showText(safe(s, font))
            cs.endText()
        }

        fun width(s: String, font: PDFont, size: Float) = font.getStringWidth(safe(s, font)) / 1000f * size

        /** Découpe en lignes tenant dans [maxWidth] (retours à la ligne conservés). */
        fun paragraph(s: String, font: PDFont, size: Float, maxWidth: Float): List<String> {
            val out = mutableListOf<String>()
            for (raw in s.split('\n')) {
                var line = ""
                for (word in raw.split(' ')) {
                    val candidate = if (line.isEmpty()) word else "$line $word"
                    if (width(candidate, font, size) <= maxWidth || line.isEmpty() && width(word, font, size) <= maxWidth) {
                        line = candidate
                        continue
                    }
                    if (line.isNotEmpty()) out += line
                    // Mot plus long que la colonne : coupé.
                    var rest = word
                    while (width(rest, font, size) > maxWidth && rest.length > 1) {
                        var n = rest.length
                        while (n > 1 && width(rest.take(n), font, size) > maxWidth) n--
                        out += rest.take(n); rest = rest.drop(n)
                    }
                    line = rest
                }
                out += line
            }
            return out
        }

        fun lineHeight(size: Float) = size * 1.25f

        fun drawLines(lines: List<String>, font: PDFont, size: Float, x: Float, top: Float, color: Color = Color.BLACK) {
            lines.forEachIndexed { i, l -> text(l, font, size, x, top + size + i * lineHeight(size), color) }
        }

        fun blockHeight(lines: List<String>, size: Float) = lines.size * lineHeight(size)

        // ---------------------------------------------------------------- mise en page
        fun header(part: PdfPart) {
            text("MaintiFlow Pro", bold, 8.5f, MARGIN, y + 10f)
            drawLines(paragraph(doc.company, regular, 7f, 125f), regular, 7f, MARGIN, y + 13f, GREY)
            val t = doc.title
            text(t, bold, 14f, MARGIN + 130f + (250f - width(t, bold, 14f)) / 2, y + 16f, NAVY)
            val right = MARGIN + contentWidth - 140f
            text(doc.number, bold, 12f, right, y + 12f, NAVY)
            drawLines(paragraph(doc.statusLabel, regular, 7f, 140f), regular, 7f, right, y + 16f, GREY)
            y += 40f
            rect(MARGIN, y, contentWidth, 15f, rgb(part.color))
            text(part.label, bold, 9.5f, MARGIN + (contentWidth - width(part.label, bold, 9.5f)) / 2, y + 11f, Color.WHITE)
            y += 23f
        }

        fun block(b: PdfBlock) {
            when (b) {
                is PdfBlock.Section -> {
                    ensure(40f)
                    rect(MARGIN, y, contentWidth, 14f, NAVY)
                    text(b.title, bold, 9.5f, MARGIN + 4f, y + 10.5f, Color.WHITE)
                    y += 14f
                }
                is PdfBlock.Grid -> {
                    val total = b.widths.sum()
                    val cols = b.widths.map { it / total * contentWidth }
                    for (row in b.rows) {
                        val laid = row.mapIndexed { i, cell ->
                            val size = if (cell.small) 7f else 8.5f
                            val font = if (cell.bold) bold else regular
                            Triple(paragraph(cell.text, font, size, cols[i] - 2 * PAD), font, size)
                        }
                        val h = (laid.maxOfOrNull { (l, _, s) -> blockHeight(l, s) } ?: 0f) + 2 * PAD
                        ensure(h)
                        var x = MARGIN
                        row.forEachIndexed { i, cell ->
                            rect(x, y, cols[i], h, if (cell.shaded) LIGHT else null, BORDER)
                            val (lines, font, size) = laid[i]
                            drawLines(lines, font, size, x + PAD, y + PAD)
                            x += cols[i]
                        }
                        y += h
                    }
                    y += 6f
                }
                is PdfBlock.Signatures -> {
                    val h = 96f
                    ensure(h)
                    val cw = contentWidth / b.cells.size
                    b.cells.forEachIndexed { i, s ->
                        val x = MARGIN + i * cw
                        rect(x, y, cw, h, null, BORDER)
                        var yy = y + PAD
                        paragraph(s.label, bold, 8.5f, cw - 2 * PAD).also { drawLines(it, bold, 8.5f, x + PAD, yy); yy += blockHeight(it, 8.5f) + 2 }
                        paragraph(s.lines.joinToString("\n"), regular, 8.5f, cw - 2 * PAD).also { drawLines(it, regular, 8.5f, x + PAD, yy); yy += blockHeight(it, 8.5f) + 2 }
                        s.image?.let { img -> image(img)?.let { fit(it, x + PAD, yy, 113f, 40f) } }
                        s.footer?.let { drawLines(paragraph(it, regular, 7f, cw - 2 * PAD), regular, 7f, x + PAD, y + h - 12f, GREY) }
                    }
                    y += h + 6f
                }
                is PdfBlock.Photos -> {
                    val h = 104f
                    ensure(h)
                    val cw = contentWidth / 4
                    b.photos.forEachIndexed { i, (bytes, label) ->
                        val x = MARGIN + i * cw
                        image(bytes)?.let { fit(it, x, y, cw - 8f, 90f) }
                        text(label, regular, 7f, x, y + h - 4f, GREY)
                    }
                    y += h + 6f
                }
                is PdfBlock.Note -> {
                    val lines = paragraph(b.text, regular, 7f, contentWidth)
                    ensure(blockHeight(lines, 7f))
                    drawLines(lines, regular, 7f, MARGIN, y, GREY)
                    y += blockHeight(lines, 7f) + 4f
                }
            }
        }

        fun image(bytes: ByteArray): PDImageXObject? = runCatching {
            val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
            LosslessFactory.createFromImage(pd, img)
        }.getOrNull()

        fun fit(img: PDImageXObject, x: Float, top: Float, w: Float, h: Float) {
            val scale = minOf(w / img.width, h / img.height)
            val dw = img.width * scale
            val dh = img.height * scale
            cs.drawImage(img, x, H - top - dh, dw, dh)
        }

        /** Remplace les caractères absents de la police (évite un échec du PDF entier). */
        fun safe(s: String, font: PDFont): String = buildString {
            s.codePoints().forEach { cp ->
                val ch = String(Character.toChars(cp))
                if (cp < 32) append(' ') else append(if (runCatching { font.encode(ch) }.isSuccess) ch else "?")
            }
        }
    }

    private fun rgb(c: Int) = Color((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
}
