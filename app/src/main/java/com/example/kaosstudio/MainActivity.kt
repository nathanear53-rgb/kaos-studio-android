package com.example.kaosstudio

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.max

class MainActivity : Activity() {

    private data class Artwork(
        var bitmap: Bitmap? = null,
        var x: Float = 300f,
        var y: Float = 390f,
        var scale: Float = 1f,
        var rotation: Float = 0f,
        var flipH: Boolean = false,
        var flipV: Boolean = false,
        var opacity: Float = 1f
    )

    private data class TextLayer(
        var text: String,
        var x: Float,
        var y: Float,
        var size: Float,
        var color: Int,
        var rotation: Float = 0f,
        var bold: Boolean = false
    )

    companion object {
        private const val DESIGN_W = 600f
        private const val DESIGN_H = 800f
        private const val REQ_IMAGE = 2001
        private const val PREFS = "kaos_studio_prefs"
        private const val PROJECT_KEY = "project"
    }

    private val shirtColors = intArrayOf(
        Color.parseColor("#FFFFFF"), Color.parseColor("#000000"), Color.parseColor("#1E88E5"),
        Color.parseColor("#E53935"), Color.parseColor("#43A047"), Color.parseColor("#8E24AA"),
        Color.parseColor("#FDD835"), Color.parseColor("#F4511E"), Color.parseColor("#546E7A"),
        Color.parseColor("#F48FB1")
    )

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: EditorView
    private lateinit var panel: LinearLayout
    private lateinit var textField: EditText
    private var textSizeValue = 48f
    private var textColorValue = Color.parseColor("#FFFFFF")
    private var textBoldValue = false
    private var selectedTextIndex = -1

    private var shirtColor = Color.parseColor("#FFFFFF")
    private var sleeveColor = Color.parseColor("#FFFFFF")
    private var neckColor = Color.parseColor("#ECEFF1")
    private var backgroundColor = Color.parseColor("#E8EAF0")
    private var style = 0
    private var guides = false
    private var art = Artwork()
    private var texts = mutableListOf<TextLayer>()

    private val history = mutableListOf<String>()
    private var historyIndex = -1
    private var restoring = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        setContentView(R.layout.activity_main)
        panel = findViewById(R.id.editorPanel)
        editor = EditorView(this)
        findViewById<FrameLayout>(R.id.editorHolder).addView(
            editor,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        editor.isClickable = true

        findViewById<Button>(R.id.btnUndo).setOnClickListener { undo() }
        findViewById<Button>(R.id.btnRedo).setOnClickListener { redo() }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportPng() }
        findViewById<Button>(R.id.btnSectionKaos).setOnClickListener { showPanel("kaos") }
        findViewById<Button>(R.id.btnSectionGambar).setOnClickListener { showPanel("gambar") }
        findViewById<Button>(R.id.btnSectionTeks).setOnClickListener { showPanel("teks") }
        findViewById<Button>(R.id.btnSectionProyek).setOnClickListener { showPanel("proyek") }

        showPanel("kaos")
        pushHistory()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun darken(color: Int, factor: Float): Int {
        return Color.rgb(
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )
    }

    private fun serialize(): JSONObject {
        val root = JSONObject()
        root.put("shirtColor", shirtColor)
        root.put("sleeveColor", sleeveColor)
        root.put("neckColor", neckColor)
        root.put("backgroundColor", backgroundColor)
        root.put("style", style)
        root.put("guides", guides)

        val a = JSONObject()
        a.put("x", art.x)
        a.put("y", art.y)
        a.put("scale", art.scale)
        a.put("rotation", art.rotation)
        a.put("flipH", art.flipH)
        a.put("flipV", art.flipV)
        a.put("opacity", art.opacity)
        val bitmap = art.bitmap
        if (bitmap != null) {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            a.put("image", Base64.encodeToString(stream.toByteArray(), Base64.DEFAULT))
        }
        root.put("art", a)

        val arr = JSONArray()
        for (item in texts) {
            val o = JSONObject()
            o.put("text", item.text)
            o.put("x", item.x)
            o.put("y", item.y)
            o.put("size", item.size)
            o.put("color", item.color)
            o.put("rotation", item.rotation)
            o.put("bold", item.bold)
            arr.put(o)
        }
        root.put("texts", arr)
        return root
    }

    private fun deserialize(root: JSONObject) {
        shirtColor = root.optInt("shirtColor", shirtColor)
        sleeveColor = root.optInt("sleeveColor", sleeveColor)
        neckColor = root.optInt("neckColor", neckColor)
        backgroundColor = root.optInt("backgroundColor", backgroundColor)
        style = root.optInt("style", style).coerceIn(0, 3)
        guides = root.optBoolean("guides", guides)

        val a = root.optJSONObject("art")
        if (a != null) {
            art.x = a.optDouble("x", art.x.toDouble()).toFloat()
            art.y = a.optDouble("y", art.y.toDouble()).toFloat()
            art.scale = a.optDouble("scale", art.scale.toDouble()).toFloat().coerceIn(0.05f, 5f)
            art.rotation = a.optDouble("rotation", art.rotation.toDouble()).toFloat()
            art.flipH = a.optBoolean("flipH", art.flipH)
            art.flipV = a.optBoolean("flipV", art.flipV)
            art.opacity = a.optDouble("opacity", art.opacity.toDouble()).toFloat().coerceIn(0.05f, 1f)
            val encoded = a.optString("image", "")
            art.bitmap = if (encoded.isEmpty()) null else {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }

        texts.clear()
        val arr = root.optJSONArray("texts")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                texts.add(
                    TextLayer(
                        o.optString("text", ""),
                        o.optDouble("x", 300.0).toFloat(),
                        o.optDouble("y", 400.0).toFloat(),
                        o.optDouble("size", 48.0).toFloat(),
                        o.optInt("color", Color.WHITE),
                        o.optDouble("rotation", 0.0).toFloat(),
                        o.optBoolean("bold", false)
                    )
                )
            }
        }
        selectedTextIndex = -1
        editor.clearSelection()
        editor.invalidate()
    }

    private fun pushHistory() {
        if (restoring) return
        val snapshot = serialize().toString()
        if (historyIndex >= 0 && history[historyIndex] == snapshot) return
        while (history.size > historyIndex + 1) history.removeAt(history.size - 1)
        history.add(snapshot)
        if (history.size > 8) history.removeAt(0)
        historyIndex = history.size - 1
    }

    private fun applyHistory(index: Int) {
        if (index < 0 || index >= history.size) return
        restoring = true
        historyIndex = index
        deserialize(JSONObject(history[index]))
        restoring = false
        editor.invalidate()
    }

    private fun undo() {
        if (historyIndex <= 0) {
            toast("Sudah di awal.")
            return
        }
        applyHistory(historyIndex - 1)
    }

    private fun redo() {
        if (historyIndex >= history.size - 1) {
            toast("Tidak ada redo.")
            return
        }
        applyHistory(historyIndex + 1)
    }

    private fun saveProject() {
        prefs.edit().putString(PROJECT_KEY, serialize().toString()).apply()
        toast("Proyek tersimpan.")
    }

    private fun openProject() {
        val raw = prefs.getString(PROJECT_KEY, null)
        if (raw == null) {
            toast("Belum ada proyek tersimpan.")
            return
        }
        restoring = true
        deserialize(JSONObject(raw))
        restoring = false
        pushHistory()
        toast("Proyek dibuka.")
    }

    private fun exportPng() {
        val bitmap = Bitmap.createBitmap(DESIGN_W.toInt(), DESIGN_H.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        editor.drawDesign(canvas)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "kaos-studio-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/KaosStudio")
            }
        }
        val uri: Uri? = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            toast("Gagal membuat file gambar.")
            return
        }
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            toast("PNG tersimpan di Galeri/KaosStudio.")
        } catch (e: Exception) {
            toast("Gagal menyimpan: ${e.message}")
        }
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_IMAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }
        val bitmap = decodeBitmap(uri, 1200)
        if (bitmap == null) {
            toast("Gambar gagal dibaca.")
            return
        }
        art.bitmap = bitmap
        art.scale = minOf(280f / bitmap.width, 430f / bitmap.height).coerceIn(0.05f, 5f)
        art.x = 300f
        art.y = 390f
        art.rotation = 0f
        editor.selectArt()
        pushHistory()
        editor.invalidate()
        toast("Gambar siap. Geser, pinch, atau putar di canvas.")
    }

    private fun decodeBitmap(uri: Uri, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (maxOf(w, h) > maxSize) {
            sample *= 2
            w /= 2
            h /= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun makeButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(3), 0, dp(3), 0) }
            setOnClickListener { action() }
        }
    }

    private fun makeLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#37474F"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(8), 0, dp(4), 0) }
        }
    }

    private fun addColorRow(label: String, colors: IntArray, onPick: (Int) -> Unit) {
        panel.addView(makeLabel(label))
        for (color in colors) {
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { setMargins(dp(2), 0, dp(2), 0) }
                setBackgroundColor(color)
                setOnClickListener { onPick(color) }
            }
            panel.addView(swatch)
        }
    }

    private fun addSeek(label: String, min: Int, max: Int, current: Int, onChange: (Int) -> Unit): SeekBar {
        panel.addView(makeLabel("$label: $current"))
        val seek = SeekBar(this).apply {
            this.max = max
            progress = current
            layoutParams = LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val valueLabel = panel.getChildAt(panel.childCount - 1) as TextView
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                valueLabel.text = "$label: $progress"
                if (fromUser) onChange(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                pushHistory()
                editor.invalidate()
            }
        })
        panel.addView(seek)
        return seek
    }

    private fun showPanel(section: String) {
        panel.removeAllViews()
        when (section) {
            "kaos" -> {
                addColorRow("Kaos", shirtColors) { shirtColor = it; pushHistory(); editor.invalidate() }
                addColorRow("Lengan", shirtColors) { sleeveColor = it; pushHistory(); editor.invalidate() }
                addColorRow("Leher", shirtColors) { neckColor = it; pushHistory(); editor.invalidate() }
                panel.addView(makeLabel("Model"))
                val models = arrayOf("O-Neck", "V-Neck", "Hoodie", "Lengan Panjang")
                models.forEachIndexed { index, name ->
                    panel.addView(makeButton(name) { style = index; pushHistory(); editor.invalidate() })
                }
                panel.addView(makeButton(if (guides) "Panduan: ON" else "Panduan: OFF") {
                    guides = !guides
                    pushHistory()
                    editor.invalidate()
                    showPanel("kaos")
                })
                addColorRow("Latar", shirtColors) { backgroundColor = it; pushHistory(); editor.invalidate() }
            }
            "gambar" -> {
                panel.addView(makeButton("Pilih Gambar") { pickImage() })
                panel.addView(makeButton("Hapus") {
                    art.bitmap = null
                    pushHistory()
                    editor.invalidate()
                })
                panel.addView(makeButton("Putar 90°") {
                    art.rotation += 90f
                    pushHistory()
                    editor.invalidate()
                })
                panel.addView(makeButton("Flip H") {
                    art.flipH = !art.flipH
                    pushHistory()
                    editor.invalidate()
                })
                panel.addView(makeButton("Flip V") {
                    art.flipV = !art.flipV
                    pushHistory()
                    editor.invalidate()
                })
                panel.addView(makeButton("Fit") {
                    val bitmap = art.bitmap
                    if (bitmap != null) {
                        art.scale = minOf(280f / bitmap.width, 430f / bitmap.height)
                        art.x = 300f
                        art.y = 390f
                        art.rotation = 0f
                        pushHistory()
                        editor.invalidate()
                    }
                })
                addSeek("Ukuran", 5, 300, (art.scale * 100).toInt().coerceIn(5, 300)) {
                    art.scale = it / 100f
                    editor.invalidate()
                }
                addSeek("Opacity", 10, 100, (art.opacity * 100).toInt()) {
                    art.opacity = it / 100f
                    editor.invalidate()
                }
            }
            "teks" -> {
                textField = EditText(this).apply {
                    hint = "Tulis teks di kaos"
                    layoutParams = LinearLayout.LayoutParams(dp(180), LinearLayout.LayoutParams.WRAP_CONTENT)
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            if (selectedTextIndex >= 0 && selectedTextIndex < texts.size) {
                                texts[selectedTextIndex].text = s.toString()
                                editor.invalidate()
                            }
                        }
                        override fun afterTextChanged(s: Editable?) {}
                    })
                }
                panel.addView(textField)
                panel.addView(makeButton("Tambah Teks") {
                    val value = textField.text.toString().ifBlank { "TEKS" }
                    texts.add(TextLayer(value, 300f, 500f, textSizeValue, textColorValue, 0f, textBoldValue))
                    selectedTextIndex = texts.size - 1
                    editor.selectText(selectedTextIndex)
                    pushHistory()
                    editor.invalidate()
                })
                panel.addView(makeButton("Hapus Teks") {
                    if (selectedTextIndex in texts.indices) {
                        texts.removeAt(selectedTextIndex)
                        selectedTextIndex = -1
                        editor.clearSelection()
                        pushHistory()
                        editor.invalidate()
                    }
                })
                panel.addView(makeButton(if (textBoldValue) "Tebal: ON" else "Tebal: OFF") {
                    textBoldValue = !textBoldValue
                    if (selectedTextIndex in texts.indices) texts[selectedTextIndex].bold = textBoldValue
                    pushHistory()
                    editor.invalidate()
                    showPanel("teks")
                })
                addColorRow("Warna teks", shirtColors) {
                    textColorValue = it
                    if (selectedTextIndex in texts.indices) texts[selectedTextIndex].color = it
                    pushHistory()
                    editor.invalidate()
                }
                addSeek("Ukuran teks", 16, 140, textSizeValue.toInt()) {
                    textSizeValue = it.toFloat()
                    if (selectedTextIndex in texts.indices) texts[selectedTextIndex].size = textSizeValue
                    editor.invalidate()
                }
            }
            "proyek" -> {
                panel.addView(makeButton("Simpan Proyek") { saveProject() })
                panel.addView(makeButton("Buka Proyek") { openProject() })
                panel.addView(makeButton("Undo") { undo() })
                panel.addView(makeButton("Redo") { redo() })
                panel.addView(makeButton("Reset") {
                    shirtColor = Color.WHITE
                    sleeveColor = Color.WHITE
                    neckColor = Color.parseColor("#ECEFF1")
                    backgroundColor = Color.parseColor("#E8EAF0")
                    style = 0
                    guides = false
                    art = Artwork()
                    texts.clear()
                    editor.clearSelection()
                    pushHistory()
                    editor.invalidate()
                })
                panel.addView(makeButton("Ekspor PNG") { exportPng() })
            }
        }
    }

    private inner class EditorView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shirtPath = Path()
        private var viewScale = 1f
        private var viewTx = 0f
        private var viewTy = 0f
        private var selected = ""
        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false
        private var pinchActive = false
        private var startDist = 1f
        private var startAngle = 0f
        private var startScale = 1f
        private var startRotation = 0f

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val w = MeasureSpec.getSize(widthMeasureSpec).toFloat()
            val h = MeasureSpec.getSize(heightMeasureSpec).toFloat()
            viewScale = minOf(w / DESIGN_W, h / DESIGN_H)
            viewTx = (w - DESIGN_W * viewScale) / 2f
            viewTy = (h - DESIGN_H * viewScale) / 2f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.save()
            canvas.translate(viewTx, viewTy)
            canvas.scale(viewScale, viewScale)
            drawDesign(canvas)
            canvas.restore()
        }

        fun drawDesign(canvas: Canvas) {
            canvas.drawColor(backgroundColor)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#11000000")
            canvas.drawOval(120f, 710f, 480f, 760f, paint)

            buildShirtPath()
            canvas.save()
            canvas.translate(8f, 10f)
            paint.color = Color.parseColor("#22000000")
            canvas.drawPath(shirtPath, paint)
            canvas.restore()

            val gradient = LinearGradient(
                0f, 140f, 0f, 730f,
                shirtColor, darken(shirtColor, 0.86f), Shader.TileMode.CLAMP
            )
            paint.shader = gradient
            canvas.drawPath(shirtPath, paint)
            paint.shader = null

            drawSleeves(canvas)
            drawNeck(canvas)

            canvas.save()
            canvas.clipPath(shirtPath)
            drawArtwork(canvas)
            drawTexts(canvas)
            canvas.restore()

            if (guides) drawGuides(canvas)
            drawSelection(canvas)
        }

        private fun buildShirtPath() {
            shirtPath.reset()
            shirtPath.moveTo(225f, 150f)
            shirtPath.quadTo(300f, 122f, 375f, 150f)
            shirtPath.lineTo(455f, 205f)
            if (style == 3) {
                shirtPath.lineTo(505f, 330f)
                shirtPath.lineTo(450f, 355f)
                shirtPath.lineTo(415f, 260f)
            } else {
                shirtPath.lineTo(410f, 255f)
                shirtPath.lineTo(385f, 225f)
            }
            shirtPath.lineTo(385f, 695f)
            shirtPath.quadTo(300f, 728f, 215f, 695f)
            shirtPath.lineTo(215f, 225f)
            if (style == 3) {
                shirtPath.lineTo(190f, 260f)
                shirtPath.lineTo(155f, 355f)
                shirtPath.lineTo(100f, 330f)
                shirtPath.lineTo(145f, 205f)
            } else {
                shirtPath.lineTo(190f, 255f)
                shirtPath.lineTo(145f, 205f)
            }
            shirtPath.close()
        }

        private fun drawSleeves(canvas: Canvas) {
            paint.style = Paint.Style.FILL
            paint.color = sleeveColor
            val left = Path()
            left.moveTo(225f, 150f)
            left.lineTo(145f, 205f)
            left.lineTo(if (style == 3) 155f else 190f, if (style == 3) 355f else 255f)
            left.lineTo(215f, 225f)
            left.close()
            canvas.drawPath(left, paint)

            val right = Path()
            right.moveTo(375f, 150f)
            right.lineTo(455f, 205f)
            right.lineTo(if (style == 3) 450f else 410f, if (style == 3) 355f else 255f)
            right.lineTo(385f, 225f)
            right.close()
            canvas.drawPath(right, paint)
        }

        private fun drawNeck(canvas: Canvas) {
            paint.style = Paint.Style.FILL
            if (style == 2) {
                paint.color = darken(shirtColor, 0.8f)
                val hood = Path()
                hood.moveTo(240f, 150f)
                hood.quadTo(300f, 205f, 360f, 150f)
                hood.quadTo(300f, 120f, 240f, 150f)
                canvas.drawPath(hood, paint)
                paint.color = darken(shirtColor, 0.65f)
                canvas.drawLine(250f, 152f, 250f, 240f, paint)
                canvas.drawLine(350f, 152f, 350f, 240f, paint)
            } else if (style == 1) {
                paint.color = backgroundColor
                val v = Path()
                v.moveTo(250f, 148f)
                v.lineTo(300f, 200f)
                v.lineTo(350f, 148f)
                v.quadTo(300f, 170f, 250f, 148f)
                canvas.drawPath(v, paint)
            } else {
                paint.color = neckColor
                canvas.drawOval(250f, 138f, 350f, 185f, paint)
                paint.color = backgroundColor
                canvas.drawOval(262f, 146f, 338f, 178f, paint)
            }
        }

        private fun drawArtwork(canvas: Canvas) {
            val bitmap = art.bitmap ?: return
            canvas.save()
            canvas.translate(art.x, art.y)
            canvas.rotate(art.rotation)
            val sx = if (art.flipH) -art.scale else art.scale
            val sy = if (art.flipV) -art.scale else art.scale
            canvas.scale(sx, sy)
            paint.alpha = (art.opacity * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(bitmap, -bitmap.width / 2f, -bitmap.height / 2f, paint)
            paint.alpha = 255
            canvas.restore()
        }

        private fun drawTexts(canvas: Canvas) {
            for (item in texts) {
                if (item.text.isBlank()) continue
                canvas.save()
                canvas.translate(item.x, item.y)
                canvas.rotate(item.rotation)
                paint.style = Paint.Style.FILL
                paint.color = item.color
                paint.textSize = item.size
                paint.textAlign = Paint.Align.CENTER
                paint.isFakeBoldText = item.bold
                canvas.drawText(item.text, 0f, item.size / 3f, paint)
                paint.isFakeBoldText = false
                paint.textAlign = Paint.Align.LEFT
                canvas.restore()
            }
        }

        private fun drawGuides(canvas: Canvas) {
            paint.style = Paint.Style.STROKE
            paint.color = Color.parseColor("#66FFFFFF")
            paint.strokeWidth = 1.5f
            canvas.drawLine(300f, 140f, 300f, 720f, paint)
            canvas.drawLine(200f, 430f, 400f, 430f, paint)
            canvas.drawRect(230f, 250f, 370f, 570f, paint)
            paint.strokeWidth = 1f
            paint.style = Paint.Style.FILL
        }

        private fun drawSelection(canvas: Canvas) {
            paint.style = Paint.Style.STROKE
            paint.color = Color.parseColor("#FF4081")
            paint.strokeWidth = 2f
            paint.pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
            if (selected == "art" && art.bitmap != null) {
                val bitmap = art.bitmap!!
                val w = bitmap.width * art.scale
                val h = bitmap.height * art.scale
                canvas.save()
                canvas.translate(art.x, art.y)
                canvas.rotate(art.rotation)
                canvas.drawRect(-w / 2f, -h / 2f, w / 2f, h / 2f, paint)
                canvas.restore()
            }
            val index = selected.removePrefix("text:").toIntOrNull()
            if (selected.startsWith("text:") && index != null && index in texts.indices) {
                val item = texts[index]
                paint.textSize = item.size
                val bounds = Rect()
                paint.getTextBounds(item.text, 0, item.text.length, bounds)
                canvas.save()
                canvas.translate(item.x, item.y)
                canvas.rotate(item.rotation)
                canvas.drawRect(
                    bounds.left.toFloat() - 8f,
                    bounds.top.toFloat() - 8f,
                    bounds.right.toFloat() + 8f,
                    bounds.bottom.toFloat() + 8f,
                    paint
                )
                canvas.restore()
            }
            paint.pathEffect = null
            paint.strokeWidth = 1f
            paint.style = Paint.Style.FILL
        }

        fun selectArt() {
            selected = "art"
        }

        fun selectText(index: Int) {
            selected = "text:$index"
        }

        fun clearSelection() {
            selected = ""
        }

        private fun toDesign(event: MotionEvent, pointer: Int = 0): Pair<Float, Float> {
            val x = (event.getX(pointer) - viewTx) / viewScale
            val y = (event.getY(pointer) - viewTy) / viewScale
            return x to y
        }

        private fun selectAt(x: Float, y: Float) {
            for (i in texts.indices.reversed()) {
                val item = texts[i]
                paint.textSize = item.size
                val bounds = Rect()
                paint.getTextBounds(item.text, 0, item.text.length, bounds)
                val rect = RectF(
                    item.x + bounds.left - 10f,
                    item.y + bounds.top - 10f,
                    item.x + bounds.right + 10f,
                    item.y + bounds.bottom + 10f
                )
                if (rect.contains(x, y)) {
                    selected = "text:$i"
                    selectedTextIndex = i
                    return
                }
            }
            val bitmap = art.bitmap
            if (bitmap != null) {
                val hw = bitmap.width * art.scale / 2f
                val hh = bitmap.height * art.scale / 2f
                if (x in art.x - hw..art.x + hw && y in art.y - hh..art.y + hh) {
                    selected = "art"
                    return
                }
            }
            selected = ""
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val (x, y) = toDesign(event)
                    selectAt(x, y)
                    lastX = x
                    lastY = y
                    dragging = true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2 && art.bitmap != null) {
                        val p0 = toDesign(event, 0)
                        val p1 = toDesign(event, 1)
                        startDist = max(1f, Math.hypot(p1.first - p0.first, p1.second - p0.second).toFloat())
                        startAngle = Math.atan2(
                            (p1.second - p0.second).toDouble(),
                            (p1.first - p0.first).toDouble()
                        ).toFloat()
                        startScale = art.scale
                        startRotation = art.rotation
                        pinchActive = true
                        selected = "art"
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pinchActive && event.pointerCount >= 2 && art.bitmap != null) {
                        val p0 = toDesign(event, 0)
                        val p1 = toDesign(event, 1)
                        val dist = max(1f, Math.hypot(p1.first - p0.first, p1.second - p0.second).toFloat())
                        val angle = Math.atan2(
                            (p1.second - p0.second).toDouble(),
                            (p1.first - p0.first).toDouble()
                        ).toFloat()
                        art.scale = (startScale * dist / startDist).coerceIn(0.05f, 5f)
                        art.rotation = startRotation + Math.toDegrees((angle - startAngle).toDouble()).toFloat()
                    } else if (dragging) {
                        val (x, y) = toDesign(event)
                        val dx = x - lastX
                        val dy = y - lastY
                        when {
                            selected == "art" -> {
                                art.x += dx
                                art.y += dy
                            }
                            selected.startsWith("text:") -> {
                                selected.removePrefix("text:").toIntOrNull()?.let { index ->
                                    if (index in texts.indices) {
                                        texts[index].x += dx
                                        texts[index].y += dy
                                    }
                                }
                            }
                        }
                        lastX = x
                        lastY = y
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    if (pinchActive) {
                        pinchActive = false
                        pushHistory()
                    } else {
                        pushHistory()
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    pinchActive = false
                    pushHistory()
                }
            }
            invalidate()
            return true
        }
    }
}
