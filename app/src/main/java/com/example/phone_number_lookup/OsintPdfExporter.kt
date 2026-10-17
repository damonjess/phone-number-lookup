package com.example.phone_number_lookup

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PdfSection(
    val heading: String,
    val lines: List<String>
)

object OsintPdfExporter {

    private const val PAGE_WIDTH = 595   // A4 width in points
    private const val PAGE_HEIGHT = 842  // A4 height in points
    private const val MARGIN = 40f
    private const val MAX_TEXT_WIDTH = PAGE_WIDTH - (MARGIN * 2)

    fun exportReport(
        context: Context,
        title: String,
        target: String,
        sections: List<PdfSection>
    ) {
        val document = PdfDocument()
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 41, 59)
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(100, 116, 139)
            textSize = 10f
        }
        val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(79, 70, 229)
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 23, 42)
            textSize = 10f
        }

        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
        var page = document.startPage(pageInfo)
        var canvas: Canvas = page.canvas
        var y = MARGIN + 16f

        fun newPage() {
            document.finishPage(page)
            pageNumber++
            pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            page = document.startPage(pageInfo)
            canvas = page.canvas
            y = MARGIN + 16f
        }

        fun drawWrappedLine(text: String, paint: Paint, lineSpacing: Float = 14f) {
            val words = text.split(' ')
            var currentLine = StringBuilder()
            for (word in words) {
                val candidate = if (currentLine.isEmpty()) word else "$currentLine $word"
                if (paint.measureText(candidate) <= MAX_TEXT_WIDTH) {
                    currentLine = StringBuilder(candidate)
                } else {
                    if (y + lineSpacing > PAGE_HEIGHT - MARGIN) newPage()
                    if (currentLine.isNotEmpty()) {
                        canvas.drawText(currentLine.toString(), MARGIN, y, paint)
                        y += lineSpacing
                    }
                    // Handle long unbroken strings like URLs
                    if (paint.measureText(word) > MAX_TEXT_WIDTH) {
                        var chunk = ""
                        for (ch in word) {
                            if (paint.measureText(chunk + ch) <= MAX_TEXT_WIDTH) {
                                chunk += ch
                            } else {
                                if (y + lineSpacing > PAGE_HEIGHT - MARGIN) newPage()
                                canvas.drawText(chunk, MARGIN, y, paint)
                                y += lineSpacing
                                chunk = ch.toString()
                            }
                        }
                        currentLine = StringBuilder(chunk)
                    } else {
                        currentLine = StringBuilder(word)
                    }
                }
            }
            if (currentLine.isNotEmpty()) {
                if (y + lineSpacing > PAGE_HEIGHT - MARGIN) newPage()
                canvas.drawText(currentLine.toString(), MARGIN, y, paint)
                y += lineSpacing
            }
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        drawWrappedLine("$title: $target", titlePaint, 22f)
        drawWrappedLine("Generated on $timestamp by OSINT Suite", subtitlePaint, 18f)
        y += 8f

        for (section in sections) {
            if (section.lines.isEmpty()) continue
            if (y + 36f > PAGE_HEIGHT - MARGIN) newPage()
            y += 6f
            drawWrappedLine(section.heading, headingPaint, 18f)
            for (line in section.lines) {
                drawWrappedLine(line, bodyPaint, 14f)
            }
            y += 6f
        }

        document.finishPage(page)

        val safeTarget = target.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(32)
        val fileName = "OSINT_${safeTarget}_${System.currentTimeMillis() / 1000}.pdf"

        val saved = runCatching {
            savePdfStream(context, fileName) { out ->
                document.writeTo(out)
            }
        }.isSuccess

        document.close()

        if (saved) {
            Toast.makeText(context, "Saved PDF to Downloads/$fileName", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Could not save PDF report", Toast.LENGTH_SHORT).show()
        }
    }

    private fun savePdfStream(context: Context, fileName: String, writeBlock: (OutputStream) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create MediaStore entry")
            resolver.openOutputStream(uri)?.use(writeBlock) ?: error("Could not open output stream")
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            FileOutputStream(File(dir, fileName)).use(writeBlock)
        }
    }
}
