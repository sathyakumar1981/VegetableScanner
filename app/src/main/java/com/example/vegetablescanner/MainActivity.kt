package com.example.vegetablescanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.vegetablescanner.databinding.ActivityMainBinding
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private enum class Mode { TEACH, USE }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var store: KnowledgeStore
    private lateinit var frameView: CropFrameView

    @Volatile private var embedder: Embedder? = null
    @Volatile private var mode = Mode.USE

    /** Size of the white frame (fraction of the shorter screen side). Changed by the slider. */
    @Volatile private var boxFraction = 0.6f

    // Teaching state (touched by the UI thread and the camera thread)
    @Volatile private var teachName: String? = null
    @Volatile private var teachRemaining = 0
    private var lastRunMs = 0L
    private val random = Random()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()
        store = KnowledgeStore(this)

        setupCropUi()
        binding.teachStatus.text = getString(R.string.teach_hint_frame)
        setupModeToggle()
        binding.learnButton.setOnClickListener { onLearnClicked() }
        binding.manageButton.setOnClickListener { showTaughtList() }

        loadModelInBackground()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ---------- Crop frame ----------

    /** Adds the white frame over the camera and a size slider to the bottom panel. */
    private fun setupCropUi() {
        // Frame sits right above the camera preview, below all buttons and panels
        frameView = CropFrameView(this).apply { fraction = boxFraction }
        binding.root.addView(
            frameView, 1,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val bar = SeekBar(this).apply {
            max = 100
            progress = ((boxFraction - 0.3f) / 0.65f * 100f).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    boxFraction = 0.3f + 0.65f * progress / 100f
                    frameView.fraction = boxFraction
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val label = TextView(this).apply {
            text = getString(R.string.frame_size)
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        val panel = binding.usePanel.parent as LinearLayout
        panel.addView(bar, 0)
        panel.addView(label, 0) // label ends up above the slider
    }

    /**
     * Cuts the camera picture down to what is inside the white frame.
     * When teaching, the cut is moved slightly at random so the app also learns the
     * vegetable at slightly different positions.
     */
    private fun cropToFrame(src: Bitmap, jitter: Boolean): Bitmap {
        val vw = binding.previewView.width.toFloat()
        val vh = binding.previewView.height.toFloat()
        if (vw <= 0f || vh <= 0f) return src

        // PreviewView fills the screen by zooming the camera picture (FILL_CENTER)
        val scale = maxOf(vw / src.width, vh / src.height)
        val boxPx = boxFraction * minOf(vw, vh)
        val side = (boxPx / scale).toInt().coerceIn(48, minOf(src.width, src.height))

        var left = (src.width - side) / 2
        var top = (src.height - side) / 2
        if (jitter) {
            val j = (side * 0.08f).toInt()
            if (j > 0) {
                left += random.nextInt(2 * j + 1) - j
                top += random.nextInt(2 * j + 1) - j
            }
        }
        left = left.coerceIn(0, src.width - side)
        top = top.coerceIn(0, src.height - side)
        return Bitmap.createBitmap(src, left, top, side, side)
    }

    // ---------- Model loading ----------

    private fun loadModelInBackground() {
        Thread {
            try {
                val bytes = ModelProvider.load(this) { msg ->
                    runOnUiThread { binding.resultText.text = msg }
                }
                embedder = Embedder(this, bytes)
                runOnUiThread { showUseIdleMessage() }
            } catch (e: Exception) {
                runOnUiThread { binding.resultText.text = getString(R.string.model_failed) }
            }
        }.start()
    }

    // ---------- Mode switching ----------

    private fun setupModeToggle() {
        binding.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (checkedId == R.id.btnTeach) setMode(Mode.TEACH) else setMode(Mode.USE)
        }
    }

    private fun setMode(newMode: Mode) {
        mode = newMode
        val teaching = newMode == Mode.TEACH
        binding.teachPanel.visibility = if (teaching) View.VISIBLE else View.GONE
        binding.usePanel.visibility = if (teaching) View.GONE else View.VISIBLE
        if (!teaching) {
            teachName = null
            teachRemaining = 0
            binding.learnButton.isEnabled = true
            hideKeyboard()
            showUseIdleMessage()
        }
    }

    private fun showUseIdleMessage() {
        if (embedder == null) return
        binding.resultText.text =
            getString(if (store.isEmpty()) R.string.nothing_taught else R.string.show_vegetable)
        binding.confidenceText.text = ""
    }

    // ---------- Teaching ----------

    private fun onLearnClicked() {
        if (embedder == null) {
            Toast.makeText(this, R.string.not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val typed = binding.nameInput.text.toString().trim()
        if (typed.isEmpty()) {
            Toast.makeText(this, R.string.enter_name, Toast.LENGTH_SHORT).show()
            return
        }
        // Re-use the existing spelling if this vegetable was taught before
        val name = store.names().firstOrNull { it.equals(typed, ignoreCase = true) } ?: typed
        hideKeyboard()
        binding.learnButton.isEnabled = false
        binding.teachStatus.text = getString(R.string.learning)
        teachName = name
        teachRemaining = SAMPLES_PER_TEACH
    }

    private fun onTeachingFinished(name: String) {
        binding.learnButton.isEnabled = true
        binding.teachStatus.text =
            getString(R.string.learned_fmt, name, store.count(name))
    }

    private fun showTaughtList() {
        val names = store.names()
        if (names.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.taught_title)
                .setMessage(R.string.taught_empty)
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }
        val items = names.map { getString(R.string.taught_item_fmt, it, store.count(it)) }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.taught_title)
            .setItems(items) { _, which -> confirmDelete(names[which]) }
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_title, name))
            .setPositiveButton(R.string.delete) { _, _ -> store.remove(name) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- Camera ----------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::analyze) }

            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        try {
            val model = embedder
            val now = System.currentTimeMillis()
            if (model == null || now - lastRunMs < FRAME_INTERVAL_MS) return

            if (mode == Mode.USE) {
                lastRunMs = now
                val crop = cropToFrame(image.toRotatedBitmap(), jitter = false)
                val match = store.bestMatch(model.embed(crop))
                runOnUiThread { showResult(match) }
            } else {
                val name = teachName ?: return
                if (teachRemaining <= 0) return
                lastRunMs = now
                val crop = cropToFrame(image.toRotatedBitmap(), jitter = true)
                store.add(name, model.embed(crop))
                teachRemaining -= 1
                if (teachRemaining == 0) {
                    store.commit()
                    teachName = null
                    runOnUiThread { onTeachingFinished(name) }
                }
            }
        } catch (_: Exception) {
            // Skip this frame
        } finally {
            image.close()
        }
    }

    private fun showResult(match: Match?) {
        when {
            match == null -> {
                binding.resultText.text = getString(R.string.nothing_taught)
                binding.confidenceText.text = ""
            }
            match.similarity >= SIMILARITY_THRESHOLD -> {
                binding.resultText.text = match.name
                binding.confidenceText.text =
                    getString(R.string.similarity_fmt, (match.similarity * 100).toInt())
            }
            else -> {
                binding.resultText.text = getString(R.string.unknown_vegetable)
                binding.confidenceText.text = ""
            }
        }
    }

    private fun ImageProxy.toRotatedBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        planes[0].buffer.rewind()
        bmp.copyPixelsFromBuffer(planes[0].buffer)
        val rotation = imageInfo.rotationDegrees
        if (rotation == 0) return bmp
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        embedder?.close()
    }

    companion object {
        /** Frames saved each time Learn is tapped (about 4 seconds of turning the vegetable). */
        private const val SAMPLES_PER_TEACH = 12
        private const val FRAME_INTERVAL_MS = 300L

        /** Minimum match (0..1) before the app is willing to name a vegetable. Tune after testing. */
        private const val SIMILARITY_THRESHOLD = 0.65f
    }
}
