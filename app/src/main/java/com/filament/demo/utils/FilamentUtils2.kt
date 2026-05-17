package com.filament.demo.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.Matrix
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.AutomationEngine
import com.google.android.filament.utils.Float3
import com.google.android.filament.utils.Manipulator
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
    private lateinit var cameraManipulator: Manipulator
    private lateinit var engine: Engine

    fun initModelViewer() {
        //这个是默认的ModelViewer构造,因为外部可能需要ModelViewer的manipulator,所以复制了一份
        engine = Engine.create()
        val targetPosition = Float3(0.0f, 0.0f, -4.0f)
        cameraManipulator = Manipulator.Builder()
            .targetPosition(targetPosition.x, targetPosition.y, targetPosition.z)
            .viewport(surfaceView.width, surfaceView.height)
            .build(Manipulator.Mode.ORBIT)
        // 初始化模型查看器
        modelViewer = ModelViewer(
            surfaceView,
            engine = engine,
            uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK),
            manipulator = cameraManipulator
        )

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
        val pinchListener = PinchScaleListener()


        val doubleTapDetector = GestureDetector(context, doubleTapListener)
        val singleTapDetector = GestureDetector(context, singleTapListener)
        val scrollDetector = GestureDetector(context, scrollListener)
        val scaleDetector = ScaleGestureDetector(context, pinchListener)

//        已知摄像机的参数是
//        cameraManipulator.getLookAt(eyePos, target, upward)
//        camera.lookAt(
//            eyePos[0], eyePos[1], eyePos[2],//[ 0.0, 0.0, 1.0 ]
//            target[0], target[1], target[2],//[ 0.0, 0.0, -4.0 ]
//            upward[0], upward[1], upward[2])//[ 0.0, 1.0, 0.0 ]

        // 设置触摸事件监听器
        surfaceView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            doubleTapDetector.onTouchEvent(event)           // 检测双击
            singleTapDetector.onTouchEvent(event)           // 检测单击
            scrollDetector.onTouchEvent(event)              // 单指模型滑动
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
        //设置定义缩放
        applyScaleToModel(0.5f)

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
     * 滑动旋转监听器
     */
    inner class ScrollRotationListener : GestureDetector.SimpleOnGestureListener() {
        // 旋转角度状态（累积值）
        private var userRotationAngle = 0f   // 水平旋转角度（绕Y轴）
        private var userPitchAngle = 0f      // 垂直旋转角度（绕X轴）

        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            // 水平旋转：左右滑动
            val deltaAngleY = -distanceX / surfaceView.width * 360f * 0.5f
            userRotationAngle = (userRotationAngle + deltaAngleY) % 360f
            if (userRotationAngle < 0) userRotationAngle += 360f

            // 垂直旋转：上下滑动
            val deltaAngleX = -distanceY / surfaceView.height * 360f * 0.5f
            userPitchAngle = (userPitchAngle + deltaAngleX) % 360f
            if (userPitchAngle < 0) userPitchAngle += 360f

            updateModelRotation()
            return true
        }

        /**
         * 更新模型旋转矩阵（只改旋转，位置保持不变）
         */
        private fun updateModelRotation() {
            modelViewer.asset?.root?.let { root ->
                val tm = modelViewer.engine.transformManager
                val instance = tm.getInstance(root)
                if (instance == 0) return

                // 1. 获取当前矩阵，提取位置
                val currentMatrix = FloatArray(16)
                tm.getTransform(instance, currentMatrix)
                val posX = currentMatrix[12]
                val posY = currentMatrix[13]
                val posZ = currentMatrix[14]

                // 2. 构建新旋转矩阵（单位矩阵 + 旋转）
                val newMatrix = FloatArray(16)
                android.opengl.Matrix.setIdentityM(newMatrix, 0)

                // 先X轴俯仰，再Y轴水平（与FilamentUtils一致）
                val rotX = FloatArray(16)
                val rotY = FloatArray(16)
                val rotComposite = FloatArray(16)
                android.opengl.Matrix.setRotateM(rotX, 0, userPitchAngle, 1f, 0f, 0f)
                android.opengl.Matrix.setRotateM(rotY, 0, userRotationAngle, 0f, 1f, 0f)
                android.opengl.Matrix.multiplyMM(rotComposite, 0, rotY, 0, rotX, 0)

                // 应用旋转到新矩阵
                android.opengl.Matrix.multiplyMM(newMatrix, 0, rotComposite, 0, newMatrix, 0)

                // 3. 把原位置写回去（只保留位置，旋转被替换）
                newMatrix[12] = posX
                newMatrix[13] = posY
                newMatrix[14] = posZ

                tm.setTransform(instance, newMatrix)
            }
        }
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


    /**
     * 双指缩放监听器 - 模型沿"模型→摄像机"向量方向移动
     */
    inner class PinchScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {

        // 灵敏度系数，可根据手感调整
        private val scaleSensitivity = 0.8f

        // 记录上一次缩放因子
        private var lastScaleFactor = 1.0f

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            lastScaleFactor = 1.0f
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor

            // 计算增量：双指张开(scale>1) → 拉近模型；捏合(scale<<1) → 拉远模型
            // 这里用对数方式计算更平滑：ln(scaleFactor) 正数表示张开，负数表示捏合
            val delta = kotlin.math.ln(scaleFactor) * scaleSensitivity

            moveModelTowardCamera(delta)

            lastScaleFactor = scaleFactor
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            lastScaleFactor = 1.0f
        }

        /**
         * 沿"模型→摄像机"方向移动模型
         * @param delta 移动量，正值向摄像机靠近，负值远离
         */
        private fun moveModelTowardCamera(delta: Float) {
            modelViewer.asset?.root?.let { root ->
                val tm = modelViewer.engine.transformManager
                val instance = tm.getInstance(root)
                if (instance == 0) return

                // 1. 获取模型当前世界位置
                val modelMatrix = FloatArray(16)
                tm.getTransform(instance, modelMatrix)
                val modelPos = floatArrayOf(modelMatrix[12], modelMatrix[13], modelMatrix[14])

                // 2. 从 Manipulator 获取摄像机位置
                val eye = FloatArray(3)
                val target = FloatArray(3)
                val upward = FloatArray(3)
                cameraManipulator.getLookAt(eye, target, upward)

                // 3. 计算"模型→摄像机"的方向向量
                val dirX = eye[0] - modelPos[0]
                val dirY = eye[1] - modelPos[1]
                val dirZ = eye[2] - modelPos[2]

                // 4. 归一化方向向量
                val length = kotlin.math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ)
                if (length < 0.0001f) return // 防止除零或已经重合

                val nx = dirX / length
                val ny = dirY / length
                val nz = dirZ / length

                // 5. 计算位移量（沿方向向量移动）
                // delta > 0: 向摄像机靠近；delta < 0: 远离摄像机
                val moveDistance = delta * length
                val offsetX = nx * moveDistance
                val offsetY = ny * moveDistance
                val offsetZ = nz * moveDistance

                // 6. 应用新位置（保持旋转不变，只改位移）
                modelMatrix[12] = modelPos[0] + offsetX
                modelMatrix[13] = modelPos[1] + offsetY
                modelMatrix[14] = modelPos[2] + offsetZ

                tm.setTransform(instance, modelMatrix)
            }
        }
    }

    /**
     * 自定义缩放
     */
    private fun applyScaleToModel(scale: Float) {
        modelViewer.asset?.root?.let { root ->
            val tm = engine.transformManager
            val instance = tm.getInstance(root)

            // 获取当前变换矩阵
            val transform = FloatArray(16)
            tm.getTransform(instance, transform)

            // 应用缩放
            Matrix.scaleM(transform, 0, scale, scale, scale)
            tm.setTransform(instance, transform)
        }
    }
}