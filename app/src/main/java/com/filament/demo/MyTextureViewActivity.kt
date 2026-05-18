package com.filament.demo

import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Choreographer
import android.view.TextureView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.filament.demo.databinding.ActivityMyTextureViewBinding
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

// 1. 移除不必要的 TextureView.SurfaceTextureListener 接口
//你不需要让 Activity 去实现 TextureView.SurfaceTextureListener，把生命周期全权交给 ModelViewer 内部去管理即可
class MyTextureViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyTextureViewBinding
    private var modelViewer: ModelViewer? = null
    private var lightEntity: Int? = null

    companion object {
        init {
            Utils.init()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMyTextureViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 2. 直接在 onCreate 中实例化 ModelViewer，它会自动给 textureView 设置监听并处理 SwapChain
        modelViewer = ModelViewer(binding.textureView)

        // 3. 配置透明背景（全套配置）
        binding.textureView.isOpaque = false
        modelViewer?.view?.blendMode = com.google.android.filament.View.BlendMode.TRANSLUCENT

        // 4. 创建光源并异步加载模型
        createLights()
        loadModelGlb()
    }

    private fun loadModelGlb() {
        lifecycleScope.launch(Dispatchers.IO) {
            val fileName = "models/helmet.glb"
            try {
                assets.open(fileName).use { input ->
                    val bytes = ByteArray(input.available())
                    input.read(bytes)
                    withContext(Dispatchers.Main) {
                        modelViewer?.let { viewer ->
                            viewer.loadModelGlb(ByteBuffer.wrap(bytes))
                            viewer.transformToUnitCube()
                            // 加载完成后不需要手动 call startRenderLoop()，因为 onResume 已经统一接管了
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createLights() {
        val viewer = modelViewer ?: return
        val scene = viewer.scene
        val engine = viewer.engine
        try {
            val mainEntity = EntityManager.get().create()
            lightEntity = mainEntity

            // 建议：检查并确保方向光的朝向是面向模型的（例如往 scene 中心看）
            LightManager.Builder(LightManager.Type.SUN)
                .color(1.0f, 1.0f, 0.95f)
                .intensity(100000.0f)
                .direction(0.0f, -1.0f, -1.0f) // 确保有一个倾斜向下的照射角度
                .castShadows(true)
                .build(engine, mainEntity)

            scene.addEntity(mainEntity)

            // 提示：如果依然全黑，建议参考官方文档加入一把间接光（IndirectLight/IBL），PBR 材质离不开 IBL
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- 渲染循环驱动 ---

    private val choreographer = Choreographer.getInstance()
    private var isRendering = false // 增加标记位防止多次 post 导致循环重叠

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (isRendering) {
                choreographer.postFrameCallback(this)
                // ModelViewer 允许在模型尚未加载完成时调用 render，它会先渲染空场景
                modelViewer?.render(frameTimeNanos)
            }
        }
    }

    private fun startRenderLoop() {
        if (!isRendering && modelViewer != null) {
            isRendering = true
            choreographer.postFrameCallback(frameCallback)
        }
    }

    private fun stopRenderLoop() {
        isRendering = false
        choreographer.removeFrameCallback(frameCallback)
    }

    override fun onResume() {
        super.onResume()
        startRenderLoop()
    }

    override fun onPause() {
        super.onPause()
        stopRenderLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 记得销毁光照实体
        lightEntity?.let {
            modelViewer?.engine?.lightManager?.destroy(it)
            EntityManager.get().destroy(it)
        }
    }
}