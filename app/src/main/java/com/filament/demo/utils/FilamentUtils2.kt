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

class FilamentUtils2(
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
        val scrollListener = ScrollRotationListener()  // 滑动旋转监听器

        val doubleTapDetector = GestureDetector(context, doubleTapListener)
        val singleTapDetector = GestureDetector(context, singleTapListener)
        val scrollDetector = GestureDetector(context, scrollListener)

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
    inner class DoubleTapListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
//            testUp90()
//            testDown90()
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

    /**
     * 滑动
     */
    inner class ScrollRotationListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {


            // 更新模型变换矩阵
            updateModelRotation()
            return true
        }
    }

    /**
     */
    private fun updateModelRotation() {

    }

    /**
     * 向上旋转90°
     */
    fun testUp90() {
        val transformManager = modelViewer.engine.transformManager
        val transformInstance = transformManager.getInstance(modelViewer.asset?.root ?: 0)
        if (transformInstance == 0) return

        // 1. 获取当前变换矩阵
        val currentMatrix = FloatArray(16)
        transformManager.getTransform(transformInstance, currentMatrix)

        // 2. 构建旋转矩阵（单位矩阵）
        val rotMatrix = FloatArray(16)
        android.opengl.Matrix.setIdentityM(rotMatrix, 0)

        // 参数说明：
        //   m      - 要操作的矩阵（float数组）
        //   mOffset- 矩阵起始偏移（通常为0）
        //   a      - 旋转角度（单位：度，不是弧度！）
        //   x,y,z  - 旋转轴向量（绕X轴就是 1,0,0；绕Y轴就是 0,1,0；绕Z轴就是 0,0,1）
        android.opengl.Matrix.rotateM(rotMatrix, 0, -90f, 1f, 0f, 0f)

        // 4. 矩阵相乘：new = current × rot
        val newMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(newMatrix, 0, currentMatrix, 0, rotMatrix, 0)

        // 5. 应用新矩阵
        transformManager.setTransform(transformInstance, newMatrix)
    }

    /**
     * 向下旋转90°
     */
    fun testDown90() {
        val transformManager = modelViewer.engine.transformManager
        val transformInstance = transformManager.getInstance(modelViewer.asset?.root ?: 0)
        if (transformInstance == 0) return

        // 1. 获取当前变换矩阵
        val currentMatrix = FloatArray(16)
        transformManager.getTransform(transformInstance, currentMatrix)

        // 2. 构建旋转矩阵（单位矩阵）
        val rotMatrix = FloatArray(16)
        android.opengl.Matrix.setIdentityM(rotMatrix, 0)

        // 参数说明：
        //   m      - 要操作的矩阵（float数组）
        //   mOffset- 矩阵起始偏移（通常为0）
        //   a      - 旋转角度（单位：度，不是弧度！）
        //   x,y,z  - 旋转轴向量（绕X轴就是 1,0,0；绕Y轴就是 0,1,0；绕Z轴就是 0,0,1）
        android.opengl.Matrix.rotateM(rotMatrix, 0, 30f, 1f, 0f, 0f)

        // 4. 矩阵相乘：new = current × rot
        val newMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(newMatrix, 0, currentMatrix, 0, rotMatrix, 0)

        // 5. 应用新矩阵
        transformManager.setTransform(transformInstance, newMatrix)
    }

}