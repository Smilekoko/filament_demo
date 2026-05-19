package com.filament.demo

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.filament.demo.databinding.ActivityMyTextureView2Binding
import com.filament.demo.utils.FilamentTextureUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MyTextureViewActivity2 : AppCompatActivity() {

    private lateinit var binding: ActivityMyTextureView2Binding
    private var filamentUtils: FilamentTextureUtils? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyTextureView2Binding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化工具类
        filamentUtils = FilamentTextureUtils(this, binding.textureView).apply {
            initModelViewer()
            setTextureViewEvent()
        }

        loadModelGlb()
        binding.buttonReset.setOnClickListener {
            filamentUtils?.resetModelTransform()
        }
    }

    private fun loadModelGlb() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                assets.open("models/helmet.glb").use { input ->
                    val bytes = input.readBytes()
                    withContext(Dispatchers.Main) {
                        filamentUtils?.loadModelGlb(bytes)
                        // 放置到合适的位置并开启自转
                        filamentUtils?.initModelPosition(1.5f, 0f)
                        filamentUtils?.setAutoRotateSpeed(-20f)
                        filamentUtils?.setAutoRotate(true)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        filamentUtils?.startRendering()
    }

    override fun onPause() {
        super.onPause()
        filamentUtils?.stopRendering()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 记得在这里释放全部的 Filament 资源，防止内存泄漏
        filamentUtils?.destroy()
    }
}