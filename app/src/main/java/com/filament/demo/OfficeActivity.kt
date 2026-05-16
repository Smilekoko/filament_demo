package com.filament.demo

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.filament.demo.databinding.ActivityOfficeBinding
import com.filament.demo.utils.FilamentUtils2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OfficeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOfficeBinding
    private var filamentUtils: FilamentUtils2? = null
    private var useLocal: Boolean=true
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityOfficeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        filamentUtils = FilamentUtils2(this, binding.surfaceView)
        filamentUtils?.initModelViewer()

        if (useLocal){
            lifecycleScope.launch(Dispatchers.IO) {
                var fileName= "models/helmet.glb"
               assets.open(fileName).use { input ->
                    val bytes = ByteArray(input.available())
                    input.read(bytes)
                   withContext(Dispatchers.Main){
                       filamentUtils?.loadModelGlb(bytes)
                       filamentUtils?.setSurfaceViewEvent()
                       filamentUtils?.startRendering()
                   }
                }
            }
        }else{
            lifecycleScope.launch(Dispatchers.IO) {
                val result = GlideDownloadUtils.downloadFileAsBytes(this@OfficeActivity, "")
                if (result.isSuccess) {
                    withContext(Dispatchers.Main) {
                        result.getOrNull()?.let {
                            filamentUtils?.loadModelGlb(it)
                            filamentUtils?.setSurfaceViewEvent()
                            filamentUtils?.startRendering()
                        }
                    }
                }
            }
        }

    }

    override fun onPause() {
        super.onPause()
        filamentUtils?.startRendering()
    }

    override fun onResume() {
        super.onResume()
        filamentUtils?.startRendering()
    }

    override fun onDestroy() {
        super.onDestroy()
        filamentUtils?.stopRendering()
    }
}