package com.filament.demo.utils

import android.annotation.SuppressLint
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceView
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.View
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.AutomationEngine
import com.google.android.filament.utils.Float3
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class FilamentUtils {

    private val TAG = this::class.java.simpleName

    companion object {
        init {
            Utils.init()
        }
    }

    private var modelViewer: ModelViewer? = null
    private var frameCallback: Choreographer.FrameCallback? = null
    private val viewerContent = AutomationEngine.ViewerContent()

    private var followLightEntity: Int = 0
    private var followLightInstance: Int = 0

    // Auto rotate
    private var isAutoRotating = false
    private var rotationSpeed = 0.1f

    // 用户手动控制的状态
    private var userRotationAngle = 0f   // 水平旋转角度（绕Y轴）
    private var userPitchAngle = 0f      // 垂直旋转角度（绕X轴），无限制
    private var baseScale = 1f//控制模型基础大小,1的模型大小话刚好屏幕宽度
    private var userScale = 0.5f//用户当前缩放大小
    private var userMaxScale = 1.5f//用户最大的缩放大小
    private var userMinScale = 0.3f//用户最小缩放发现
    private var initialUserScale: Float = 0.5f//几率初始化的大小

    // 模型基础变换,会影响旋转中心
    var basePosition = floatArrayOf(0f, 0f, 0f)


    // 相机控制
    private var cameraDistance = 8.0f
    private var cameraTarget = floatArrayOf(0f, 0f, 0f)

    // 触摸交互相关
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastTouchDistance = 0f
    private var isMultiTouch = false

    // 双击检测
    private var lastClickTime = 0L
    private var lastClickX = 0f
    private var lastClickY = 0f
    var doubleTapListener: (() -> Unit)? = null

    // 单击检查
    private val mainHandler = Handler(Looper.getMainLooper())
    private val singleTapRunnable = Runnable { singleTapListener?.invoke() }
    var singleTapListener: (() -> Unit)? = null

    // 触摸拖动检测（新增）
    private var hasDragged = false
    private var downX = 0f
    private var downY = 0f

    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }
    private val engine by lazy { Engine.create() }
    private val uiHelper by lazy { UiHelper().apply { isOpaque = false } }

    fun setModelViewer(surfaceView: SurfaceView) {
        modelViewer = ModelViewer(surfaceView, engine, uiHelper)

        modelViewer?.let {
            viewerContent.view = it.view
            viewerContent.sunlight = it.light
            viewerContent.lightManager = it.engine.lightManager
            viewerContent.scene = it.scene
            viewerContent.renderer = it.renderer
        }

        modelViewer?.renderer.let { renderer ->
            renderer?.clearOptions = Renderer.ClearOptions().apply {
                clearColor = floatArrayOf(0f, 0f, 0f, 0f)
                clear = true
                discard = false
            }
        }
        modelViewer?.view?.blendMode = View.BlendMode.TRANSLUCENT

        setFollowLight()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun setTouchEvent(surfaceView: SurfaceView) {
        surfaceView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    isMultiTouch = false
                    mainHandler.removeCallbacks(singleTapRunnable)

                    // 新增：记录按下位置，重置拖动标志
                    downX = event.x
                    downY = event.y
                    hasDragged = false

                    true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        isMultiTouch = true
                        lastTouchDistance = getDistance(event)
                        mainHandler.removeCallbacks(singleTapRunnable)
                        // 新增：双指操作视为拖动，不触发单击
                        hasDragged = true
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isMultiTouch && event.pointerCount == 1) {
                        // 新增：检测是否发生了有效移动（拖动）
                        if (!hasDragged) {
                            val dxMove = event.x - downX
                            val dyMove = event.y - downY
                            val movedDistance = sqrt(dxMove * dxMove + dyMove * dyMove)
                            if (movedDistance > 10f) { // 阈值 10px，可根据需要调整
                                hasDragged = true
                            }
                        }

                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        if (abs(dx) > 0f || abs(dy) > 0f) {
                            // 水平旋转：左右滑动
                            val deltaAngleY = dx / surfaceView.width * 360f * 0.5f
                            userRotationAngle = (userRotationAngle + deltaAngleY) % 360f
                            if (userRotationAngle < 0) userRotationAngle += 360f

                            // 垂直旋转：上下滑动，方向与滑动一致（向上滑动抬头）
                            val deltaAngleX = dy / surfaceView.height * 360f * 0.5f
                            userPitchAngle = (userPitchAngle + deltaAngleX) % 360f
                            if (userPitchAngle < 0) userPitchAngle += 360f

                            if (isAutoRotating && (abs(dx) > 2f || abs(dy) > 2f)) {
                                setAutoRotate(false)
                            }
                            applyUserTransform()
                        }
                        lastTouchX = event.x
                        lastTouchY = event.y
                    } else if (isMultiTouch && event.pointerCount >= 2) {
                        val currentDistance = getDistance(event)
                        if (lastTouchDistance > 0f) {
                            val scaleFactor = currentDistance / lastTouchDistance
                            Log.d(TAG, "scaleFactor=$scaleFactor")
                            val newScale = userScale * scaleFactor
                            if (newScale != userScale) {
                                userScale = newScale
                                applyUserTransform()
                            }
                        }
                        lastTouchDistance = currentDistance
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    isMultiTouch = false
                    true
                }

                MotionEvent.ACTION_UP -> {
                    // 如果发生过拖动（单指移动超过阈值）或是多指操作，则不触发任何单击/双击回调
                    if (hasDragged || isMultiTouch) {
                        // 清除可能待执行的单击任务
                        mainHandler.removeCallbacks(singleTapRunnable)
                        // 重置上次点击记录，避免后续误判
                        lastClickTime = 0
                        // 重置拖动标志
                        hasDragged = false
                        isMultiTouch = false
                        return@setOnTouchListener true
                    }

                    // 正常无拖动无多指时，才检测单击/双击
                    checkClickType(event)
                    isMultiTouch = false
                    true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.pointerCount == 1) {
                        isMultiTouch = false
                        val index = if (event.actionIndex == 0) 1 else 0
                        lastTouchX = event.getX(index)
                        lastTouchY = event.getY(index)
                    }
                    true
                }

                else -> false
            }
        }
    }

    // 判断单击还是双击
    private fun checkClickType(event: MotionEvent) {
        // 单指抬起时检测单击/双击
        if (!isMultiTouch && event.pointerCount == 1) {
            val currentTime = System.currentTimeMillis()
            val deltaTime = currentTime - lastClickTime
            val deltaX = abs(event.x - lastClickX)
            val deltaY = abs(event.y - lastClickY)

            if (deltaTime in 1..300 && deltaX < 20f && deltaY < 20f) {
                // 检测到双击：取消延迟的单击任务，触发双击回调，重置状态
                mainHandler.removeCallbacks(singleTapRunnable)
                doubleTapListener?.invoke()
                lastClickTime = 0
            } else {
                // 不是双击：记录本次点击，并延迟触发单击
                lastClickTime = currentTime
                lastClickX = event.x
                lastClickY = event.y
                mainHandler.removeCallbacks(singleTapRunnable)
                mainHandler.postDelayed(singleTapRunnable, 300)
            }
        }
        isMultiTouch = false
    }

    private fun getDistance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun applyUserTransform() {
        modelViewer?.asset?.root?.let { root ->
            val tm = engine.transformManager
            val instance = tm.getInstance(root)

            val transform = FloatArray(16)
            Matrix.setIdentityM(transform, 0)

            var finalScale = min(baseScale * userScale, userMaxScale)
            finalScale = max(finalScale, userMinScale)
            Matrix.scaleM(transform, 0, finalScale, finalScale, finalScale)
//            LogUtils.d("FilamentUtils", "finalScale=$finalScale")

            // 构建旋转矩阵：先绕 X 轴（俯仰），再绕 Y 轴（水平）
            val rotX = FloatArray(16)
            val rotY = FloatArray(16)
            val rotComposite = FloatArray(16)

            Matrix.setRotateM(rotX, 0, userPitchAngle, 1f, 0f, 0f)
            Matrix.setRotateM(rotY, 0, userRotationAngle, 0f, 1f, 0f)

            // 注意矩阵乘法顺序：先应用 RX，再 RY
            Matrix.multiplyMM(rotComposite, 0, rotY, 0, rotX, 0)

            // 应用旋转
            Matrix.multiplyMM(transform, 0, rotComposite, 0, transform, 0)

            // 平移到基础位置
            Matrix.translateM(transform, 0, basePosition[0], basePosition[1], basePosition[2])

            tm.setTransform(instance, transform)
        }
    }

    private fun setFollowLight() {
        modelViewer?.let {
            val mainEntity = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.SUN)
                .direction(basePosition[0], basePosition[1], basePosition[2] - 10f)
                .color(1.0f, 1.0f, 0.85f)
                .intensity(100000.0f)
                .castShadows(false)
                .build(it.engine, mainEntity)

//            val followEntity = EntityManager.get().create()
//            LightManager.Builder(LightManager.Type.POINT)
//                .direction(0.0f, 0f, -1f)
//                .color(1.0f, 1.0f, 0.8f)
//                .intensity(10000.0f)
//                .castShadows(false)
//                .build(it.engine, followEntity)

            followLightEntity = mainEntity
            followLightInstance = it.engine.lightManager.getInstance(followLightEntity)
            it.scene.addEntities(
                intArrayOf(
                    mainEntity,
//                    followEntity
                )
            )
        }
    }

    private fun updateLightFollowCamera() {
        if (followLightInstance != 0) {
            modelViewer?.let {
                val f = it.camera.getForwardVector(null)
                it.engine.lightManager.setDirection(followLightInstance, f[0], f[1], f[2])
            }
        }
    }

    private fun autoSetCameraForModel() {
        modelViewer?.let { viewer ->
            val eyeX = cameraTarget[0]
            val eyeY = cameraTarget[1]
            val eyeZ = cameraTarget[2] + cameraDistance
//            Log.d(
//                "FilamentUtils",
//                "force camera: (${eyeX},${eyeY},${eyeZ}), distance=$cameraDistance"
//            )
            viewer.camera.lookAt(
                eyeX.toDouble(), eyeY.toDouble(), eyeZ.toDouble(),
                cameraTarget[0].toDouble(), cameraTarget[1].toDouble(), cameraTarget[2].toDouble(),
                0.0, 1.0, 0.0
            )
        }
    }

    private fun forceSetCamera() {
        modelViewer?.camera?.let { camera ->
            val eyeX = cameraTarget[0]
            val eyeY = cameraTarget[1]
            val eyeZ = cameraTarget[2] + cameraDistance
            val centerX = cameraTarget[0]
            val centerY = cameraTarget[1]
            val centerZ = cameraTarget[2]

            camera.lookAt(
                eyeX.toDouble(), eyeY.toDouble(), eyeZ.toDouble(),
                centerX.toDouble(), centerY.toDouble(), centerZ.toDouble(),
                0.0, 1.0, 0.0
            )
        }
    }

    fun startRendering() {
        frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
            updateLightFollowCamera()
            forceSetCamera()

            // 自动旋转：仅水平方向
            if (isAutoRotating) {
                userRotationAngle = (userRotationAngle + rotationSpeed) % 360f
                if (userRotationAngle < 0) userRotationAngle += 360f
                applyUserTransform()
            }

            modelViewer?.render(frameTimeNanos)
            frameCallback?.let { choreographer.postFrameCallback(it) }
        }
        frameCallback?.let { choreographer.postFrameCallback(it) }
    }

    /**
     * 加载模型并自动归一化、缩放、设置合适相机
     * @param scale 模型整体缩放,影响最小大小
     * @param userScale 模型加载后,显示用户的缩放大小
     * @param userMaxScale 模型加载后,用户能控制的最大缩放
     */
    fun loadMode(
        byteArray: ByteArray,
        scale: Float = 1f,
        userScale: Float = 0.50f,
        userMinScale: Float = 0.3f,
        userMaxScale: Float = 1.5f,
    ) {

        try {
            baseScale = scale
            userRotationAngle = 0f
            userPitchAngle = 0f
            this.userScale = userScale
            this.initialUserScale = userScale
            this.userMinScale = userMinScale
            this.userMaxScale = userMaxScale

            val buffer = ByteBuffer.wrap(byteArray)
            modelViewer?.loadModelGlb(buffer)
            // 保证模型中心在原点
            modelViewer?.transformToUnitCube(
                Float3(basePosition[0], basePosition[1], basePosition[2])
            )

            applyUserTransform()
            autoSetCameraForModel()
            forceSetCamera()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setCameraDistance(distance: Float) {
        cameraDistance = distance
    }

    fun setAutoRotate(enable: Boolean, speed: Float = 0.35f) {
        isAutoRotating = enable
        rotationSpeed = speed
    }

    fun release() {
        mainHandler.removeCallbacks(singleTapRunnable)
        frameCallback?.let { choreographer.removeFrameCallback(it) }

        if (followLightEntity != 0) {
            try {
                modelViewer?.scene?.remove(followLightEntity)
                engine.destroyEntity(followLightEntity)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        modelViewer?.let {
            try {
                it.scene.entities.forEach { en ->
                    engine.destroyEntity(en)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        try {
            uiHelper.detach()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        modelViewer = null
        frameCallback = null
        followLightEntity = 0
        followLightInstance = 0
    }

    fun pauseRendering() {
        frameCallback?.let { choreographer.removeFrameCallback(it) }
    }

    fun resumeRendering() {
        frameCallback?.let { choreographer.postFrameCallback(it) }
    }

    fun reset() {
        // 重置旋转角度
        userRotationAngle = 0f
        userPitchAngle = 0f

        // 重置用户缩放至初始值
        userScale = initialUserScale

        // 应用变换
        applyUserTransform()

        // 开启自动旋转（速度保持当前值，或可指定默认速度）
        setAutoRotate(true)
    }
}