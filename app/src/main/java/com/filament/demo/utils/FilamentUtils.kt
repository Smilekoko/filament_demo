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
import com.google.android.filament.utils.AutomationEngine
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

    //这个为啥不直接更改相机实现自旋转,因为官方的渲染器会强行读取手势管理器（Manipulator）的位置，并把相机的矩阵再次重写覆盖掉。
    //Manipulator又没有方便旋转的方法
    var modelAutoRotate: Boolean = true//模型是否矩阵变换实现自旋转

    private val viewerContent = AutomationEngine.ViewerContent() // 查看器内容容器

    private var loadedModelEntity: Int = 0 // 用于存储模型的根实体

    fun initModelViewer() {
        // 初始化模型查看器
        modelViewer = ModelViewer(surfaceView)
        // 填充viewerContent对象，供自动化引擎使用
        viewerContent.view = modelViewer.view
        viewerContent.sunlight = modelViewer.light
        viewerContent.lightManager = modelViewer.engine.lightManager
        viewerContent.scene = modelViewer.scene
        viewerContent.renderer = modelViewer.renderer

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
        modelViewer.transformToUnitCube()
        modelViewer
        //设置光照
        setFollowLight()
        //获取模型句柄
        val asset = modelViewer.asset
        if (asset != null) {
            // 2. 获取资产的根实体。对于单个根节点的模型，根实体就是模型本身。
            //    asset.getRoot() 返回根实体的索引。
            loadedModelEntity = asset.entities.find { it != followLightEntity } ?: 0
        } else {
            // 处理 asset 为 null 的情况，例如加载失败或模型文件为空
            loadedModelEntity = 0
        }
        println(loadedModelEntity)
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

            if (modelAutoRotate) {
                //todo
            }
            updateLightFollowCamera()//光照跟随相机

            modelViewer.render(frameTimeNanos)// 渲染当前帧
        }
    }

    fun startRendering() {
        choreographer.postFrameCallback(frameScheduler)     // 注册帧回调
    }

    fun stopRendering() {
        choreographer.removeFrameCallback(frameScheduler)   // 移除帧回调
    }

}