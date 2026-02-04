package com.filament.demo

import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Choreographer
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.filament.demo.databinding.ActivityTest2Binding
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Skybox
import com.google.android.filament.View
import com.google.android.filament.utils.AutomationEngine
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer


class TestActivity2 : AppCompatActivity(), TextureView.SurfaceTextureListener {

    companion object {
        // 初始化 Filament 工具库
        init {
            Utils.init()
        }
    }

    private lateinit var textureView: TextureView
    private lateinit var modelViewer: ModelViewer
    private lateinit var choreographer: Choreographer
    private lateinit var frameCallback: Choreographer.FrameCallback
    private val viewerContent = AutomationEngine.ViewerContent() // 查看器内容容器

    private lateinit var binding: ActivityTest2Binding
    private var followLightEntity: Int = 0
    private var followLightInstance: Int = 0

    // 标记渲染是否已开始
    private var isRenderingStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.enableEdgeToEdge()
        binding = ActivityTest2Binding.inflate(layoutInflater)
        setContentView(binding.root)

        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        textureView = binding.textureView // 确保布局中id为textureView

        // 设置TextureView为透明
        textureView.isOpaque = false
        textureView.alpha = 1f

        // 设置SurfaceTexture监听器
        textureView.surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        // SurfaceTexture可用时初始化ModelViewer
        initModelViewer(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // SurfaceTexture尺寸变化时调整渲染器
//        modelViewer.view.viewport = View.Viewport(0, 0, width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // 停止渲染循环
        stopRendering()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // TextureView更新时的处理（可选）
    }

    private fun initModelViewer(surface: SurfaceTexture, width: Int, height: Int) {
        // 使用自定义的SurfaceTexture创建ModelViewer
        modelViewer = ModelViewer(textureView)
//        modelViewer.view.viewport = View.Viewport(0, 0, width, height)

        // 填充viewerContent对象，供自动化引擎使用
        viewerContent.view = modelViewer.view
        viewerContent.sunlight = modelViewer.light
        viewerContent.lightManager = modelViewer.engine.lightManager
        viewerContent.scene = modelViewer.scene
        viewerContent.renderer = modelViewer.renderer

        // 通过 Renderer.ClearOptions 设置透明清除色 (alpha = 0)
        modelViewer.renderer.let { renderer ->
            renderer.clearOptions = Renderer.ClearOptions().apply {
                clearColor = floatArrayOf(0f, 0f, 0f, 0f)
                clear = true
                discard = false
            }
        }
        modelViewer.view.blendMode = View.BlendMode.TRANSLUCENT

        // 设置一个浅蓝色天空盒（也会作为视觉背景）
        setupSkybox()

        val directional = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .direction(0.0f, 0f, -1f)
            .color(1.0f, 1.0f, 1.0f)
            .intensity(120000.0f)
            .castShadows(false)
            .build(modelViewer.engine, directional)

        followLightEntity = directional
        followLightInstance = modelViewer.engine.lightManager.getInstance(followLightEntity)

        modelViewer.scene.addEntities(
            intArrayOf(
                directional
            )
        )

        val size = modelViewer.scene.entities
        println(size)

        // 设置触摸事件处理
        textureView.setOnTouchListener { _, event ->
            modelViewer.onTouchEvent(event)
            true
        }

        // 获取 Choreographer 实例用于帧同步
        choreographer = Choreographer.getInstance()

        // 加载 GLB 模型
        loadGLBModel()

        // 开始渲染循环
        startRendering()
    }

    private fun startRendering() {
        if (isRenderingStarted) return

        frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
            updateLightFollowCamera()
            modelViewer.render(frameTimeNanos)
            choreographer.postFrameCallback(frameCallback)
        }
        choreographer.postFrameCallback(frameCallback)
        isRenderingStarted = true
    }

    private fun stopRendering() {
        if (::frameCallback.isInitialized) {
            choreographer.removeFrameCallback(frameCallback)
        }
        isRenderingStarted = false
    }

    override fun onResume() {
        super.onResume()
        if (isRenderingStarted && ::frameCallback.isInitialized) {
            choreographer.postFrameCallback(frameCallback)
        }
    }

    override fun onPause() {
        super.onPause()
        stopRendering()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRendering()

        // 销毁并清理天空盒
        skybox?.let { modelViewer.engine.destroySkybox(it) }
        skybox = null
    }

    /**
     * 从 assets 加载 GLB 模型文件
     */
    private fun loadGLBModel() {
        try {
            val modelPath = "models/helmet.glb"
            val buffer = assets.open(modelPath).use { input ->
                val bytes = ByteArray(input.available())
                input.read(bytes)
                ByteBuffer.wrap(bytes)
            }

            modelViewer.loadModelGlb(buffer)
            modelViewer.transformToUnitCube()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 持有天空盒实例以便在销毁时清理
    private var skybox: Skybox? = null

    /**
     * 创建并设置一个浅蓝色（LightSkyBlue）的 Skybox
     */
    private fun setupSkybox() {
        val r = 0f / 255f
        val g = 206f / 255f
        val b = 250f / 255f
        val a = 1.0f

        skybox = Skybox.Builder()
            .color(floatArrayOf(r, g, b, a))
            .build(modelViewer.engine)

        modelViewer.scene.skybox = skybox
    }

    private fun updateLightFollowCamera() {
        if (followLightInstance != 0) {
            val f = modelViewer.camera.getForwardVector(null)
            modelViewer.engine.lightManager.setDirection(followLightInstance, f[0], f[1], f[2])
        }
    }
}