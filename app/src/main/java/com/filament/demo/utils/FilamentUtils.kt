package com.filament.demo.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceView
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.View
import com.google.android.filament.utils.Float3
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer

class FilamentUtils(
    val context: Context,
    val surfaceView: SurfaceView
) {
    // 初始化工具库，这会加载gltfio和Filament核心库
    init {
        Utils.init()
    }

    private val choreographer = Choreographer.getInstance()
    private val frameScheduler = FrameCallback()//注册调动器
    private lateinit var modelViewer: ModelViewer
    var autoRotation: Boolean = true

    fun initModelViewer() {
        // 初始化模型查看器
        modelViewer = ModelViewer(surfaceView)
        //设置背景透明
        modelViewer.renderer.let { renderer ->
            renderer.clearOptions = Renderer.ClearOptions().apply {
                clearColor = floatArrayOf(0f, 0f, 0f, 0f)
                clear = true
                discard = false
            }
        }
        modelViewer.view.blendMode = View.BlendMode.TRANSLUCENT
        surfaceView.holder.setFormat(PixelFormat.TRANSPARENT)
        surfaceView.setZOrderOnTop(true)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun setSurfaceViewEvent() {
        val doubleTapListener = DoubleTapListener()
        val singleTapListener = SingleTapListener()
        val doubleTapDetector = GestureDetector(context, doubleTapListener)
        val singleTapDetector = GestureDetector(context, singleTapListener)
        // 设置触摸事件监听器
        surfaceView.setOnTouchListener { _, event ->
            modelViewer.onTouchEvent(event)                 // 传递触摸事件给模型查看器
            doubleTapDetector.onTouchEvent(event)           // 检测双击
            singleTapDetector.onTouchEvent(event)           // 检测单击
            true
        }
    }

    /**
     * 双击监听器
     */
    class DoubleTapListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            return super.onDoubleTap(e)
        }
    }

    /**
     * 单击监听器
     */
    class SingleTapListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            return super.onSingleTapUp(event)
        }
    }


    fun loadModelGlb(byteArray: ByteArray) {
        modelViewer.loadModelGlb(ByteBuffer.wrap(byteArray))
        modelViewer.transformToUnitCube(Float3(0.0f, 0.0f, -4.0f))
        //设置光照
        setFollowLight()
    }

    private var followLightEntity: Int = 0
    private var followLightInstance: Int = 0
    private fun setFollowLight() {
        modelViewer.let {
            val mainEntity = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.SUN)
                .direction(0f, 0f, 0f)
                .color(1.0f, 1.0f, 0.85f)
                .intensity(100000.0f)
                .castShadows(false)
                .build(it.engine, mainEntity)

            followLightEntity = mainEntity
            followLightInstance = it.engine.lightManager.getInstance(followLightEntity)
            it.scene.addEntities(
                intArrayOf(mainEntity)
            )
        }
    }

    private fun updateLightFollowCamera() {
        if (followLightInstance != 0) {
            modelViewer.let {
                val f = it.camera.getForwardVector(null)
                it.engine.lightManager.setDirection(followLightInstance, f[0], f[1], f[2])
            }
        }
    }

    /**
     * 帧回调类，处理每帧的渲染和更新逻辑
     */
    inner class FrameCallback : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographer.postFrameCallback(this)// 注册下一帧回调
            modelViewer.render(frameTimeNanos)// 渲染当前帧
            updateLightFollowCamera()//光照跟随相机
        }
    }

    fun startRendering() {
        choreographer.postFrameCallback(frameScheduler)     // 注册帧回调
    }

    fun stopRendering(){
        choreographer.removeFrameCallback(frameScheduler)   // 移除帧回调
    }
}