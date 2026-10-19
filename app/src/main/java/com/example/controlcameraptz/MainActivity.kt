package com.robotx30.controlcameraptz

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var libVLC: LibVLC
    private var mediaPlayerRgb: MediaPlayer? = null
    private var mediaPlayerThermal: MediaPlayer? = null
    private val ptzController = PTZController()

    // URLs RTSP del sistema (Canal 1 Visible + Canal 2 Térmico)
    private val rtspRgbUrl = "rtsp://admin:admin@192.168.1.108:554/cam/realmonitor?channel=1&subtype=0"
    private val rtspThermalUrl = "rtsp://admin:admin@192.168.1.108:554/cam/realmonitor?channel=2&subtype=0"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        hideSystemUI()

        checkPermissions()
        setupPtzModes()
        setupSwitches()
        setupTouchPad()
        initLibVlcStreams()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val permissions = arrayOf(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            )
            val needed = permissions.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                requestPermissions(needed.toTypedArray(), 101)
            }
        }
    }

    private fun setupPtzModes() {
        val spinner = findViewById<Spinner>(R.id.spinnerPtzMode)
        val modes = arrayOf("ABSOLUTE PTZ", "Preset", "Tour", "Auto Scan", "Pattern", "Aux", "Auto Pan")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        spinner.adapter = adapter
    }

    private fun setupSwitches() {
        val switchHideData = findViewById<SwitchMaterial>(R.id.switchHideData)
        val overlayCh1 = findViewById<LinearLayout>(R.id.overlayCh1)
        val overlayCh2 = findViewById<LinearLayout>(R.id.overlayCh2)

        // Lógica del botón HIDE DATA para ocultar/mostrar superposiciones
        switchHideData.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                overlayCh1.visibility = View.GONE
                overlayCh2.visibility = View.GONE
            } else {
                overlayCh1.visibility = View.VISIBLE
                overlayCh2.visibility = View.VISIBLE
            }
        }

        val switchAi = findViewById<SwitchMaterial>(R.id.switchAi)
        switchAi.setOnCheckedChangeListener { _, isChecked ->
            val msg = if (isChecked) "AI Detection Enabled" else "AI Detection Disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupTouchPad() {
        // Movimientos continuos táctiles (Press & Hold para mover, Release para detener)
        setupPtzButton(R.id.btnUp, "Up")
        setupPtzButton(R.id.btnDown, "Down")
        setupPtzButton(R.id.btnLeft, "Left")
        setupPtzButton(R.id.btnRight, "Right")

        setupPtzButton(R.id.btnUpLeft, "LeftUp")
        setupPtzButton(R.id.btnUpRight, "RightUp")
        setupPtzButton(R.id.btnDownLeft, "LeftDown")
        setupPtzButton(R.id.btnDownRight, "RightDown")

        setupPtzButton(R.id.btnZoomIn, "ZoomTele")
        setupPtzButton(R.id.btnZoomOut, "ZoomWide")

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            updatePtzCredentialsFromUi()
            CoroutineScope(Dispatchers.Main).launch { ptzController.stopMove("Up") }
        }

        findViewById<Button>(R.id.btnTakeFoto).setOnClickListener {
            takeDualPhoto()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPtzButton(buttonId: Int, code: String) {
        val button = findViewById<Button>(buttonId) ?: return
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    updatePtzCredentialsFromUi()
                    lifecycleScope.launch {
                        val result = ptzController.startMove(code)
                        if (result != "OK") {
                            Toast.makeText(this@MainActivity, "PTZ: $result", Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    lifecycleScope.launch {
                        ptzController.stopMove(code)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun updatePtzCredentialsFromUi() {
        try {
            val input = findViewById<EditText>(R.id.etIpVideo).text.toString().trim()
            if (input.isNotEmpty()) {
                if (input.contains("@")) {
                    val userPassPart = input.split("@")[0].replace("rtsp://", "").replace("http://", "")
                    if (userPassPart.contains(":")) {
                        ptzController.username = userPassPart.split(":")[0]
                        ptzController.password = userPassPart.split(":")[1]
                    }
                }
                ptzController.cameraIp = input
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun takeDualPhoto() {
        updatePtzCredentialsFromUi()
        lifecycleScope.launch(Dispatchers.IO) {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

            // 1. Captura Canal 1 - Visible RGB (CGI directo primero)
            val bytesRgb = ptzController.takeSnapshot(1)
            var bitmapRgb: Bitmap? = null
            if (bytesRgb == null) {
                withContext(Dispatchers.Main) {
                    val layoutRgb = findViewById<VLCVideoLayout>(R.id.videoRgb)
                    bitmapRgb = captureViewBitmap(layoutRgb)
                }
            }
            val savedRgb = saveToGallery(bytesRgb, bitmapRgb, "PTZ_RGB_$timestamp.jpg")

            // 2. Captura Canal 2 - Térmico
            val bytesThermal = ptzController.takeSnapshot(2)
            var bitmapThermal: Bitmap? = null
            if (bytesThermal == null) {
                withContext(Dispatchers.Main) {
                    val layoutThermal = findViewById<VLCVideoLayout>(R.id.videoThermal)
                    bitmapThermal = captureViewBitmap(layoutThermal)
                }
            }
            val savedThermal = saveToGallery(bytesThermal, bitmapThermal, "PTZ_Thermal_$timestamp.jpg")

            withContext(Dispatchers.Main) {
                if (savedRgb && savedThermal) {
                    Toast.makeText(
                        this@MainActivity,
                        "📷 Fotos guardadas (RGB y Térmica) en Galería",
                        Toast.LENGTH_LONG
                    ).show()
                } else if (savedRgb || savedThermal) {
                    Toast.makeText(
                        this@MainActivity,
                        "📷 Una foto guardada en Galería",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "⚠️ Error al guardar capturas",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun saveToGallery(
        bytes: ByteArray?,
        bitmap: Bitmap?,
        filename: String
    ): Boolean {
        if (bytes == null && bitmap == null) return false

        // 1. Intentar guardar en MediaStore (Galería pública)
        try {
            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ControlCameraPTZ")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (imageUri != null) {
                resolver.openOutputStream(imageUri)?.use { outputStream ->
                    if (bytes != null) {
                        outputStream.write(bytes)
                    } else if (bitmap != null) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(imageUri, contentValues, null, null)
                }
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Fallback de Almacenamiento Externo de la App (garantiza que NUNCA falle)
        return try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
            val file = File(dir, filename)
            FileOutputStream(file).use { out ->
                if (bytes != null) {
                    out.write(bytes)
                } else if (bitmap != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private suspend fun captureViewBitmap(view: View): Bitmap? = suspendCancellableCoroutine { continuation ->
        if (view.width <= 0 || view.height <= 0) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val surfaceView = findSurfaceView(view)
        if (surfaceView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(
                surfaceView,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        continuation.resume(bitmap)
                    } else {
                        continuation.resume(null)
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val location = IntArray(2)
            view.getLocationInWindow(location)
            val rect = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
            PixelCopy.request(
                window,
                rect,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        continuation.resume(bitmap)
                    } else {
                        continuation.resume(null)
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } else {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            continuation.resume(bitmap)
        }
    }

    private fun findSurfaceView(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = findSurfaceView(view.getChildAt(i))
                if (child != null) return child
            }
        }
        return null
    }

    private fun initLibVlcStreams() {
        val options = ArrayList<String>().apply {
            add("--network-caching=150")
            add("--rtsp-tcp")
        }

        libVLC = LibVLC(this, options)

        // Stream RGB (Canal 1)
        val layoutRgb = findViewById<VLCVideoLayout>(R.id.videoRgb)
        mediaPlayerRgb = MediaPlayer(libVLC).apply {
            attachViews(layoutRgb, null, false, false)
            media = Media(libVLC, Uri.parse(rtspRgbUrl)).apply {
                setHWDecoderEnabled(true, false)
            }
            play()
        }

        // Stream Térmico (Canal 2)
        val layoutThermal = findViewById<VLCVideoLayout>(R.id.videoThermal)
        mediaPlayerThermal = MediaPlayer(libVLC).apply {
            attachViews(layoutThermal, null, false, false)
            media = Media(libVLC, Uri.parse(rtspThermalUrl)).apply {
                setHWDecoderEnabled(true, false)
            }
            play()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayerRgb?.release()
        mediaPlayerThermal?.release()
        libVLC.release()
    }
}
