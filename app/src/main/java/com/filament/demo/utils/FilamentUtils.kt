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
        val scrollListener = ScrollRotationListener()  // 新增滑动监听器

        val doubleTapDetector = GestureDetector(context, doubleTapListener)
        val singleTapDetector = GestureDetector(context, singleTapListener)
        val scrollDetector = GestureDetector(context, scrollListener)  // 用于检测滑动

        // 设置触摸事件监听器
        surfaceView.setOnTouchListener { _, event ->
            doubleTapDetector.onTouchEvent(event)           // 检测双击
            singleTapDetector.onTouchEvent(event)           // 检测单击
            scrollDetector.onTouchEvent(event)
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


    // 模型当前的旋转角度（绕 X 轴和 Y 轴，单位：弧度）
    private var modelRotationX: Float = 0f
    private var modelRotationY: Float = 0f

    /**
     * 滑动旋转模型监听器
     */
    inner class ScrollRotationListener : GestureDetector.SimpleOnGestureListener() {
        private val ROTATION_SENSITIVITY = 0.005f   // 灵敏度，可调整

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {

            modelRotationX += distanceY * ROTATION_SENSITIVITY * 0.01f

            updateModelRotation()
            return true
        }
    }

    /**
     * 更新模型的旋转矩阵
     */
    private fun updateModelRotation() {
        val transformManager = modelViewer.engine.transformManager
        val transformInstance = transformManager.getInstance(modelViewer.asset?.root ?: 0)
        if (transformInstance == 0) return

        // 获取当前变换矩阵（可能是 modelViewer.transformToUnitCube() 设置的）
        val currentMatrix = FloatArray(16)
        transformManager.getTransform(transformInstance, currentMatrix)

        // 构建旋转矩阵
        val rotMatrix = FloatArray(16)
        android.opengl.Matrix.setIdentityM(rotMatrix, 0)
        android.opengl.Matrix.rotateM(rotMatrix, 0, Math.toDegrees(modelRotationY.toDouble()).toFloat(), 0f, 1f, 0f)
        android.opengl.Matrix.rotateM(rotMatrix, 0, Math.toDegrees(modelRotationX.toDouble()).toFloat(), 1f, 0f, 0f)

        // 组合：newTransform = currentMatrix * rotMatrix
        val newMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(newMatrix, 0, currentMatrix, 0, rotMatrix, 0)
        transformManager.setTransform(transformInstance, newMatrix)
    }

}