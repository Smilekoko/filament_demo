package com.filament.demo

import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Choreographer
import android.view.TextureView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.filament.demo.databinding.ActivityTest2Binding
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import com.google.android.filament.View as FilamentView

class TestActivity2 : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var binding: ActivityTest2Binding

    private var modelViewer: ModelViewer? = null
    private var asset: FilamentAsset? = null

    companion object {
        // 初始化 Filament 工具库
        init {
            Utils.init()
        }
    }

    // Frame loop
    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val mv = modelViewer
            if (mv != null) {
                // Advance animations if present
                mv.animator?.let { animator ->
                    val animCount = try { animator.animationCount } catch (_: Exception) { 0 }
                    if (animCount > 0 && animationStartTimeNanos != 0L) {
                        val ms = (System.nanoTime() - animationStartTimeNanos) / 1_000_000L
                        try {
                            animator.applyAnimation(0, ms / 1000.0f)
                            animator.updateBoneMatrices()
                        } catch (_: Exception) {
                            // ignore if animation index missing etc.
                        }
                    }
                }

                // Render the frame. This writes RGBA into the swapchain.
                try {
                    mv.render(frameTimeNanos)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // schedule next frame
            choreographer.postFrameCallback(this)
        }
    }

    // Time when last GLB was loaded (for animations)
    private var animationStartTimeNanos: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityTest2Binding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Ensure TextureView reports it's non-opaque so the system will composite its alpha.
        binding.textureView.isOpaque = true
        binding.textureView.surfaceTextureListener = this

    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        // Create ModelViewer using the TextureView (ModelViewer will create/own Engine/View)
        modelViewer = ModelViewer(binding.textureView)

        // Make sure the Filament view used by ModelViewer is set to translucent blending.
        modelViewer?.view?.setBlendMode(FilamentView.BlendMode.TRANSLUCENT)

        // Ensure the renderer clears to transparent and DOES write the alpha to the swapchain.
        modelViewer?.renderer?.clearOptions = com.google.android.filament.Renderer.ClearOptions().apply {
            clearColor = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
            discard = false
            clear = true
        }

        // IMPORTANT: disable skybox/background so Filament doesn't render an opaque background
        modelViewer?.scene?.skybox = null
        modelViewer?.scene?.indirectLight = null

        startRenderLoop()

        // Load model off the UI thread and then instantiate on UI thread
        thread {
            val bb = readAssetIntoByteBuffer("models/helmet.glb")
            runOnUiThread {
                loadGlbModel(bb)
            }
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stopRenderLoop()

        // Destroy model + Filament objects managed by ModelViewer
        try {
            modelViewer?.destroyModel()
        } catch (_: Exception) {}
        // We don't explicitly destroy the ModelViewer engine here (ModelViewer may own it).
        modelViewer = null
        asset = null

        return true
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // no-op; ModelViewer listens to view size changes
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRenderLoop()

        modelViewer?.destroyModel()
        modelViewer = null
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // not used
    }

    private fun loadGlbModel(buffer: ByteBuffer?) {
        if (buffer == null) {
            android.util.Log.e("TestActivity2", "loadGlbModel: buffer is null")
            return
        }

        // 确保 position 在 0
        try {
            buffer.position(0)
        } catch (_: Exception) {}

        val mv = modelViewer
        if (mv == null) {
            android.util.Log.e("TestActivity2", "loadGlbModel: modelViewer is null")
            return
        }

        // 销毁旧模型（如果有）
        try {
            mv.destroyModel()
        } catch (_: Exception) {}

        try {
            mv.loadModelGlb(buffer)
            android.util.Log.d("TestActivity2", "loadModelGlb succeeded. animatorCount=${mv.animator?.animationCount ?: 0}")

            // 关闭 skybox 避免遮挡
            mv.scene.skybox = null
            mv.scene.indirectLight = null

            val animCount = try { mv.animator?.animationCount ?: 0 } catch (e: Exception) { 0 }
            animationStartTimeNanos = if (animCount > 0) System.nanoTime() else 0L

            // framing to unit cube (if available)
            try {
                mv.transformToUnitCube()
            } catch (e: Exception) {
                android.util.Log.w("TestActivity2", "framing failed: ${e.message}")
            }
        } catch (e: Exception) {
            android.util.Log.e("TestActivity2", "Error in loadModelGlb: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun startRenderLoop() {
        // ensure only one loop
        choreographer.removeFrameCallback(frameCallback)
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopRenderLoop() {
        choreographer.removeFrameCallback(frameCallback)
    }

    private fun readAssetIntoByteBuffer(assetName: String): ByteBuffer? {
        return try {
            assets.open(assetName).use { input ->
                val baos = java.io.ByteArrayOutputStream()
                val buf = ByteArray(4096)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    baos.write(buf, 0, read)
                }
                val bytes = baos.toByteArray()
                android.util.Log.d("TestActivity2", "readAsset: $assetName size=${bytes.size}")

                // 使用 direct buffer 并设置 native order
                val bb = ByteBuffer.allocateDirect(bytes.size).order(java.nio.ByteOrder.nativeOrder())
                bb.put(bytes)
                bb.flip() // position -> 0, limit -> size
                bb
            }
        } catch (e: Exception) {
            android.util.Log.e("TestActivity2", "readAssetIntoByteBuffer failed: ${e.message}")
            e.printStackTrace()
            null
        }
    }
}