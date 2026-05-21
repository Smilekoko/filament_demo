package com.filament.demo

import android.os.Bundle
import android.view.TextureView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.filament.demo.databinding.ActivityFilamentCenterFixBinding
import com.filament.demo.utils.FilamentCenterFixUtils
import com.filament.demo.utils.FilamentTextureUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FilamentCenterFixActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFilamentCenterFixBinding
    private var filamentUtils: FilamentCenterFixUtils? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. 使用 ViewBinding 初始化视图布局
        binding = ActivityFilamentCenterFixBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 处理全面屏系统栏边界间距
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 2. 传入对应的 TextureView 初始化 3D 渲染工具类
        filamentUtils = FilamentCenterFixUtils(this, binding.textureView).apply {
            initModelViewer()
            setTextureViewEvent()
        }

        // 3. 异步读取并加载 3D 模型
        loadModelGlb()

    }

    /**
     * 在后台线程读取 Assets 中的 3D 模型文件并分发至 Filament 进行渲染
     */
    private fun loadModelGlb() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 替换为你项目中实际使用的模型路径，如 "models/helmet.glb"
                assets.open("models/wawa.glb").use { input ->
                    val bytes = input.readBytes()
                    withContext(Dispatchers.Main) {
                        filamentUtils?.let { utils ->
                            utils.loadModelGlb(bytes)
                            // 修正初始化姿态：距离系数 1.5倍，无Y轴额外偏移
                            utils.initModelPosition(2.5f, 0f)
                            // 设置自转速度并默认开启自传
                            utils.setAutoRotateSpeed(-20f)
                            utils.setAutoRotate(true)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 页面可见时恢复 Choreographer 帧渲染循环
        filamentUtils?.startRendering()
    }

    override fun onPause() {
        super.onPause()
        // 页面不可见时暂停帧循环，节省硬件功耗
        filamentUtils?.stopRendering()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Activity 销毁时务必安全释放 Filament 底层 C++ 内存实体，防止内存泄漏
        filamentUtils?.release()
    }
}