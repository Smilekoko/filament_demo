package com.filament.demo.utils

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.Matrix
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.TextureView
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
import kotlin.math.ln
import kotlin.math.sqrt

class FilamentUtils3(
    val context: Context,
    val textureView: TextureView
) {
    init {
        Utils.init()
    }

    private val choreographer = Choreographer.getInstance()
    private val frameScheduler = FrameCallback()
    lateinit var modelViewer: ModelViewer

    private val viewerContent = AutomationEngine.ViewerContent()
    private lateinit var cameraManipulator: Manipulator
    private lateinit var engine: Engine

    private enum class GestureMode { NONE, PAN, SCALE }

    private var currentMode = GestureMode.NONE
    private var initialDistance = 0f
    private var lastDistance = 0f
    private var initialCenterX = 0f
    private var initialCenterY = 0f
    private var lastCenterX = 0f
    private var lastCenterY = 0f

    private val scaleTriggerThreshold = 0.02f
    private val panTriggerThreshold = 8f
    private lateinit var uiHelper: UiHelper

    // 用于保存模型独立于“几何中心修正”之外的世界坐标（真正的平移位置）
    private var worldPosX = 0f
    private var worldPosY = 0f
    private var worldPosZ = 0f

    fun initModelViewer() {
        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
        engine = Engine.create()
        val targetPosition = Float3(0.0f, 0.0f, -4.0f)
        cameraManipulator = Manipulator.Builder()
            .targetPosition(targetPosition.x, targetPosition.y, targetPosition.z)
            .viewport(textureView.width, textureView.height)
            .build(Manipulator.Mode.ORBIT)

        modelViewer = ModelViewer(
            textureView,
            engine = engine,
            uiHelper = uiHelper,
            manipulator = cameraManipulator
        )

        viewerContent.view = modelViewer.view
        viewerContent.sunlight = modelViewer.light
        viewerContent.lightManager = modelViewer.engine.lightManager
        viewerContent.scene = modelViewer.scene
        viewerContent.renderer = modelViewer.renderer

        modelViewer.renderer.let { renderer ->
            renderer.clearOptions = Renderer.ClearOptions().apply {
                clearColor = floatArrayOf(0f, 0f, 0f, 0f)
                clear = true
                discard = false
            }
        }

        textureView.isOpaque = false
        modelViewer.view.blendMode = View.BlendMode.TRANSLUCENT
    }

    @SuppressLint("ClickableViewAccessibility")
    fun setTextureViewEvent() {
        val doubleTapListener = DoubleTapListener()
        val singleTapListener = SingleTapListener()
        val scrollListener = ScrollRotationListener()

        val doubleTapDetector = GestureDetector(context, doubleTapListener)
        val singleTapDetector = GestureDetector(context, singleTapListener)
        val scrollDetector = GestureDetector(context, scrollListener)

        textureView.setOnTouchListener { _, event ->
            val pointerCount = event.pointerCount

            // 核心修改 1：只要有任意手指初次按下或多指按下，且当时正在自动旋转
            // 就在最开始的瞬间将自动旋转的角度同步继承给用户操控的角度，实现无缝切换
            if ((event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) && isAutoRotating) {
                setAutoRotate(false)
                userRotationAngle = autoRotateAngle
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (pointerCount == 2) {
                        currentMode = GestureMode.NONE

                        val (x0, y0) = getPointerCenter(event)
                        initialCenterX = x0
                        initialCenterY = y0
                        lastCenterX = x0
                        lastCenterY = y0

                        initialDistance = getPointerDistance(event)
                        lastDistance = initialDistance
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (pointerCount == 2) {
                        when (currentMode) {
                            GestureMode.NONE -> {
                                val currentDistance = getPointerDistance(event)
                                val distanceRatio = currentDistance / initialDistance
                                val scaleDelta = Math.abs(distanceRatio - 1f)

                                val (cx, cy) = getPointerCenter(event)
                                val panDeltaX = cx - initialCenterX
                                val panDeltaY = cy - initialCenterY
                                val panDistance = sqrt(panDeltaX * panDeltaX + panDeltaY * panDeltaY)

                                if (scaleDelta > scaleTriggerThreshold) {
                                    currentMode = GestureMode.SCALE
                                    lastDistance = currentDistance
                                } else if (panDistance > panTriggerThreshold) {
                                    currentMode = GestureMode.PAN
                                    lastCenterX = cx
                                    lastCenterY = cy
                                }
                            }

                            GestureMode.SCALE -> {
                                val currentDistance = getPointerDistance(event)
                                if (currentDistance > 0.01f && lastDistance > 0.01f) {
                                    val scaleFactor = currentDistance / lastDistance
                                    val delta = ln(scaleFactor) * 0.8f
                                    moveModelTowardCamera(delta)
                                }
                                lastDistance = currentDistance
                            }

                            GestureMode.PAN -> {
                                val (cx, cy) = getPointerCenter(event)
                                val deltaX = cx - lastCenterX
                                val deltaY = cy - lastCenterY
                                applyPan(deltaX, deltaY)
                                lastCenterX = cx
                                lastCenterY = cy
                            }
                        }
                    }
                }

                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pointerCount <= 2) {
                        currentMode = GestureMode.NONE
                    }
                }
            }

            if (pointerCount == 1 && currentMode == GestureMode.NONE) {
                scrollDetector.onTouchEvent(event)
                doubleTapDetector.onTouchEvent(event)
                singleTapDetector.onTouchEvent(event)
            }

            true
        }
    }

    private fun getPointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun getPointerCenter(event: MotionEvent): Pair<Float, Float> {
        if (event.pointerCount < 2) return Pair(0f, 0f)
        val x = (event.getX(0) + event.getX(1)) / 2f
        val y = (event.getY(0) + event.getY(1)) / 2f
        return Pair(x, y)
    }

    private fun moveModelTowardCamera(delta: Float) {
        val eye = FloatArray(3)
        val target = FloatArray(3)
        val upward = FloatArray(3)
        cameraManipulator.getLookAt(eye, target, upward)

        val dirX = eye[0] - worldPosX
        val dirY = eye[1] - worldPosY
        val dirZ = eye[2] - worldPosZ
        val length = sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ)
        if (length < 0.0001f) return

        val moveDistance = delta * length
        worldPosX += (dirX / length) * moveDistance
        worldPosY += (dirY / length) * moveDistance
        worldPosZ += (dirZ / length) * moveDistance

        applyTransform()
    }

    private fun applyPan(deltaScreenX: Float, deltaScreenY: Float) {
        val sensitivity = 0.003f
        worldPosX += deltaScreenX * sensitivity
        worldPosY -= deltaScreenY * sensitivity
        applyTransform()
    }

    inner class DoubleTapListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean = true
    }

    var onSingleClick: () -> Unit = {}

    inner class SingleTapListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            onSingleClick.invoke()
            return super.onSingleTapUp(event)
        }
    }

    fun loadModelGlb(byteArray: ByteArray) {
        modelViewer.loadModelGlb(ByteBuffer.wrap(byteArray))
        modelViewer.transformToUnitCube()
        setFollowLight()
    }

    private var followLightEntity: Int = 0
    private var followLightInstance: Int = 0
    private fun setFollowLight() {
        modelViewer.let {
            modelViewer.scene.removeEntities(intArrayOf(modelViewer.light))
            val mainEntity = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.SUN)
                .direction(0f, 0f, 0f)
                .color(1.0f, 1.0f, 0.85f)
                .intensity(100000.0f)
                .castShadows(false)
                .build(it.engine, mainEntity)

            followLightEntity = mainEntity
            followLightInstance = it.engine.lightManager.getInstance(followLightEntity)
            it.scene.addEntities(intArrayOf(mainEntity))
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

    fun startRendering() {
        if (::modelViewer.isInitialized) {
            choreographer.postFrameCallback(frameScheduler)
        }
    }

    fun stopRendering() {
        if (::modelViewer.isInitialized) {
            choreographer.removeFrameCallback(frameScheduler)
        }
    }

    fun release() {
        stopRendering()
        if (::modelViewer.isInitialized) {
            if (followLightEntity != 0) {
                try {
                    modelViewer.scene.remove(followLightEntity)
                    engine.destroyEntity(followLightEntity)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            modelViewer.let {
                try {
                    it.scene.entities.forEach { en -> engine.destroyEntity(en) }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            try {
                uiHelper.detach()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private var userPitchAngle = 0f
    private var userRotationAngle = 0f

    inner class ScrollRotationListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            // 核心修改 2：针对单指滑动的核心兼容拦截
            // 如果手势触发时正在自动旋转，在此处立刻继承角度并停止自动旋转，确保顺滑接管
            if (isAutoRotating) {
                setAutoRotate(false)
                userRotationAngle = autoRotateAngle
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            // 核心修改 3：因为在 onDown / ACTION_DOWN 中已经对 userRotationAngle 进行了继承重置
            // 此处直接基于被继承的角度进行滑动增量累加，彻底去掉了以前的 `userRotationAngle = -autoRotateAngle` 符号反转逻辑
            val deltaAngleY = -distanceX / textureView.width * 360f * 0.5f
            userRotationAngle = (userRotationAngle + deltaAngleY) % 360f
            if (userRotationAngle < 0) userRotationAngle += 360f

            val deltaAngleX = -distanceY / textureView.height * 360f * 0.5f
            userPitchAngle = (userPitchAngle + deltaAngleX) % 360f
            if (userPitchAngle < 0) userPitchAngle += 360f

            applyTransform()
            return true
        }
    }

    /**
     * 核心变换矩阵计算：
     * 公式：变换矩阵 = 真实平移矩阵 * 手势旋转矩阵 * 缩放矩阵 * (-中心点位置偏置)
     */
    private fun applyTransform() {
        val asset = modelViewer.asset ?: return
        val root = asset.root
        val tm = modelViewer.engine.transformManager
        val instance = tm.getInstance(root)
        if (instance == 0) return

        val box = asset.boundingBox
        val centerX = box.center[0]
        val centerY = box.center[1]
        val centerZ = box.center[2]

        // 核心修改 4：无论是自动旋转还是手动旋转，全部复用统一的 userRotationAngle 调度
        // 自动旋转时通过时钟修改此值，手势时通过滑动修改此值
        val finalYaw = if (isAutoRotating) autoRotateAngle else userRotationAngle
        val rotX = FloatArray(16)
        val rotY = FloatArray(16)
        val rotationComposite = FloatArray(16)
        Matrix.setRotateM(rotX, 0, userPitchAngle, 1f, 0f, 0f)
        Matrix.setRotateM(rotY, 0, finalYaw, 0f, 1f, 0f)
        Matrix.multiplyMM(rotationComposite, 0, rotY, 0, rotX, 0)

        val finalMatrix = FloatArray(16)
        Matrix.setIdentityM(finalMatrix, 0)

        // 1. 世界平移
        Matrix.translateM(finalMatrix, 0, worldPosX, worldPosY, worldPosZ)

        // 2. 轴心旋转
        Matrix.multiplyMM(finalMatrix, 0, finalMatrix, 0, rotationComposite, 0)

        // 3. 基础缩放比例
        Matrix.scaleM(finalMatrix, 0, scaleFactor, scaleFactor, scaleFactor)

        // 4. 纠正几何中心点带来的晃动偏差
        Matrix.translateM(finalMatrix, 0, -centerX, -centerY, -centerZ)

        tm.setTransform(instance, finalMatrix)
    }

    fun testUp90() {
        userPitchAngle = (userPitchAngle - 90f) % 360f
        applyTransform()
    }

    fun testDown90() {
        userPitchAngle = (userPitchAngle + 30f) % 360f
        applyTransform()
    }

    private var currentDistanceFactor: Float = 1.0f
    private var currentOffsetY: Float = 0f
    private var scaleFactor: Float = 1.0f

    fun initModelPosition(distanceFactor: Float, offsetY: Float = 0f) {
        currentDistanceFactor = distanceFactor
        currentOffsetY = offsetY

        val asset = modelViewer.asset ?: return
        val root = asset.root
        val tm = modelViewer.engine.transformManager
        val instance = tm.getInstance(root)
        if (instance == 0) return

        modelViewer.transformToUnitCube()

        val tempMatrix = FloatArray(16)
        tm.getTransform(instance, tempMatrix)
        scaleFactor = sqrt(tempMatrix[0] * tempMatrix[0] + tempMatrix[1] * tempMatrix[1] + tempMatrix[2] * tempMatrix[2])

        val eye = FloatArray(3)
        val target = FloatArray(3)
        val upward = FloatArray(3)
        cameraManipulator.getLookAt(eye, target, upward)

        val dirX = 0f - eye[0]
        val dirY = 0f - eye[1]
        val dirZ = -4f - eye[2]

        val length = sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ)
        val (nx, ny, nz) = if (length < 0.0001f) Triple(0f, 0f, -1f) else Triple(dirX / length, dirY / length, dirZ / length)

        val baseDistance = 2f * distanceFactor
        worldPosX = eye[0] + nx * baseDistance
        worldPosY = eye[1] + ny * baseDistance + offsetY
        worldPosZ = eye[2] + nz * baseDistance

        applyTransform()
    }

    private var autoRotateAngle = 0f
    private var autoRotateSpeed = 30f
    var isAutoRotating = false

    fun setAutoRotateSpeed(degreesPerSecond: Float) {
        autoRotateSpeed = degreesPerSecond
    }

    fun setAutoRotate(enabled: Boolean) {
        isAutoRotating = enabled
    }

    private fun updateAutoRotation(dt: Float) {
        // 自动旋转时更新 autoRotateAngle 角度
        autoRotateAngle = (autoRotateAngle + autoRotateSpeed * dt) % 360f
        if (autoRotateAngle < 0) autoRotateAngle += 360f
        applyTransform()
    }

    inner class FrameCallback : Choreographer.FrameCallback {
        private var lastFrameTimeNanos = 0L
        override fun doFrame(frameTimeNanos: Long) {
            choreographer.postFrameCallback(this)
            val dt = if (lastFrameTimeNanos == 0L) 0f else {
                ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f).coerceAtMost(0.1f)
            }
            lastFrameTimeNanos = frameTimeNanos
            if (isAutoRotating) {
                updateAutoRotation(dt)
            }
            updateLightFollowCamera()
            modelViewer.render(frameTimeNanos)
        }
    }

    fun resetModelTransform() {
        userRotationAngle = 0f
        userPitchAngle = 0f
        autoRotateAngle = 0f
        initModelPosition(currentDistanceFactor, currentOffsetY)
        setAutoRotate(true)
    }
}