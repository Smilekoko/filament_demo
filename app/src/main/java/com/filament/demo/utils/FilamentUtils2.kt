package com.filament.demo.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.Matrix
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceView
import android.widget.Toast
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
import kotlin.math.sqrt

class FilamentUtils2(
    val context: Context,
    val surfaceView: SurfaceView
) {
    init {
        Utils.init()
    }

    private val choreographer = Choreographer.getInstance()
    private val frameScheduler = FrameCallback()
    private lateinit var modelViewer: ModelViewer

    private val viewerContent = AutomationEngine.ViewerContent()
    private lateinit var cameraManipulator: Manipulator
    private lateinit var engine: Engine

    // 手势模式锁定
    private enum class GestureMode { NONE, PAN, SCALE }

    private var currentMode = GestureMode.NONE
    private var initialDistance = 0f
    private var lastDistance = 0f
    private var initialCenterX = 0f
    private var initialCenterY = 0f
    private var lastCenterX = 0f
    private var lastCenterY = 0f

    // 优化后的阈值（平移更易触发，缩放更难误触）
    private val scaleTriggerThreshold = 0.02f   // 距离比例变化 >2% 才触发缩放
    private val panTriggerThreshold = 8f        // 中心点移动 >8 像素即触发平移

    fun initModelViewer() {
        engine = Engine.create()
        val targetPosition = Float3(0.0f, 0.0f, -4.0f)
        cameraManipulator = Manipulator.Builder()
            .targetPosition(targetPosition.x, targetPosition.y, targetPosition.z)
            .viewport(surfaceView.width, surfaceView.height)
            .build(Manipulator.Mode.ORBIT)
        modelViewer = ModelViewer(
            surfaceView,
            engine = engine,
            uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK),
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
        modelViewer.view.blendMode = View.BlendMode.TRANSLUCENT
        surfaceView.holder.setFormat(PixelFormat.TRANSPARENT)
        surfaceView.setZOrderOnTop(true)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun setSurfaceViewEvent() {
        val doubleTapListener = DoubleTapListener()
        val singleTapListener = SingleTapListener()
        val scrollListener = ScrollRotationListener()

        val doubleTapDetector = GestureDetector(context, doubleTapListener)
        val singleTapDetector = GestureDetector(context, singleTapListener)
        val scrollDetector = GestureDetector(context, scrollListener)

        surfaceView.setOnTouchListener { _, event ->
            val pointerCount = event.pointerCount

            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (pointerCount == 2) {
                        currentMode = GestureMode.NONE
                        if (isAutoRotating) {
                            setAutoRotate(false)
                            userRotationAngle = -autoRotateAngle
                        }

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
                                val panDistance =
                                    sqrt(panDeltaX * panDeltaX + panDeltaY * panDeltaY)

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
                                    val delta = kotlin.math.ln(scaleFactor) * 0.8f
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
        modelViewer.asset?.root?.let { root ->
            val tm = modelViewer.engine.transformManager
            val instance = tm.getInstance(root)
            if (instance == 0) return

            val modelMatrix = FloatArray(16)
            tm.getTransform(instance, modelMatrix)
            val modelPos = floatArrayOf(modelMatrix[12], modelMatrix[13], modelMatrix[14])

            val eye = FloatArray(3)
            val target = FloatArray(3)
            val upward = FloatArray(3)
            cameraManipulator.getLookAt(eye, target, upward)

            val dirX = eye[0] - modelPos[0]
            val dirY = eye[1] - modelPos[1]
            val dirZ = eye[2] - modelPos[2]
            val length = sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ)
            if (length < 0.0001f) return

            val nx = dirX / length
            val ny = dirY / length
            val nz = dirZ / length

            val moveDistance = delta * length
            modelMatrix[12] = modelPos[0] + nx * moveDistance
            modelMatrix[13] = modelPos[1] + ny * moveDistance
            modelMatrix[14] = modelPos[2] + nz * moveDistance

            tm.setTransform(instance, modelMatrix)
        }
    }

    private fun applyPan(deltaScreenX: Float, deltaScreenY: Float) {
        val sensitivity = 0.003f
        modelViewer.asset?.root?.let { root ->
            val tm = modelViewer.engine.transformManager
            val instance = tm.getInstance(root)
            if (instance == 0) return
            val currentMatrix = FloatArray(16)
            tm.getTransform(instance, currentMatrix)
            currentMatrix[12] += deltaScreenX * sensitivity
            currentMatrix[13] -= deltaScreenY * sensitivity
            tm.setTransform(instance, currentMatrix)
        }
    }

    // 以下为原有其他方法（未修改，保持完整）
    inner class DoubleTapListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            Toast.makeText(surfaceView.context, "双击", Toast.LENGTH_SHORT).show()
            resetModelTransform()
            return super.onDoubleTap(e)
        }
    }

    inner class SingleTapListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            Toast.makeText(surfaceView.context, "单击", Toast.LENGTH_SHORT).show()
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
        choreographer.postFrameCallback(frameScheduler)
    }

    fun stopRendering() {
        choreographer.removeFrameCallback(frameScheduler)
    }

    private var userPitchAngle = 0f
    private var userRotationAngle = 0f

    inner class ScrollRotationListener : GestureDetector.SimpleOnGestureListener() {


        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            val deltaAngleY = -distanceX / surfaceView.width * 360f * 0.5f
            userRotationAngle = (userRotationAngle + deltaAngleY) % 360f
            if (userRotationAngle < 0) userRotationAngle += 360f

            val deltaAngleX = -distanceY / surfaceView.height * 360f * 0.5f
            userPitchAngle = (userPitchAngle + deltaAngleX) % 360f
            if (userPitchAngle < 0) userPitchAngle += 360f

            if (isAutoRotating) {
                setAutoRotate(false)
                userRotationAngle = -autoRotateAngle
            }
            updateModelRotation()
            return true
        }

        private fun updateModelRotation() {
            modelViewer.asset?.root?.let { root ->
                val tm = modelViewer.engine.transformManager
                val instance = tm.getInstance(root)
                if (instance == 0) return

                val currentMatrix = FloatArray(16)
                tm.getTransform(instance, currentMatrix)

                // 提取位置
                val posX = currentMatrix[12]
                val posY = currentMatrix[13]
                val posZ = currentMatrix[14]

                // 提取缩放（各轴的长度）
                val scaleX = sqrt(
                    currentMatrix[0] * currentMatrix[0] +
                            currentMatrix[1] * currentMatrix[1] +
                            currentMatrix[2] * currentMatrix[2]
                )
                val scaleY = sqrt(
                    currentMatrix[4] * currentMatrix[4] +
                            currentMatrix[5] * currentMatrix[5] +
                            currentMatrix[6] * currentMatrix[6]
                )
                val scaleZ = sqrt(
                    currentMatrix[8] * currentMatrix[8] +
                            currentMatrix[9] * currentMatrix[9] +
                            currentMatrix[10] * currentMatrix[10]
                )

                // 构造旋转矩阵（注意：绕 X 和 Y 轴旋转的顺序与自动旋转保持一致）
                val rotX = FloatArray(16)
                val rotY = FloatArray(16)
                val rotComposite = FloatArray(16)
                Matrix.setRotateM(rotX, 0, userPitchAngle, 1f, 0f, 0f)
                Matrix.setRotateM(rotY, 0, userRotationAngle, 0f, 1f, 0f)
                Matrix.multiplyMM(rotComposite, 0, rotY, 0, rotX, 0)

                // 组合缩放、旋转、平移
                val newMatrix = FloatArray(16)
                Matrix.setIdentityM(newMatrix, 0)
                // 应用缩放
                newMatrix[0] = rotComposite[0] * scaleX
                newMatrix[1] = rotComposite[1] * scaleX
                newMatrix[2] = rotComposite[2] * scaleX
                newMatrix[4] = rotComposite[4] * scaleY
                newMatrix[5] = rotComposite[5] * scaleY
                newMatrix[6] = rotComposite[6] * scaleY
                newMatrix[8] = rotComposite[8] * scaleZ
                newMatrix[9] = rotComposite[9] * scaleZ
                newMatrix[10] = rotComposite[10] * scaleZ
                // 设置位置
                newMatrix[12] = posX
                newMatrix[13] = posY
                newMatrix[14] = posZ

                tm.setTransform(instance, newMatrix)
            }
        }
    }

    fun testUp90() {
        val transformManager = modelViewer.engine.transformManager
        val transformInstance = transformManager.getInstance(modelViewer.asset?.root ?: 0)
        if (transformInstance == 0) return

        val currentMatrix = FloatArray(16)
        transformManager.getTransform(transformInstance, currentMatrix)
        val rotMatrix = FloatArray(16)
        Matrix.setIdentityM(rotMatrix, 0)
        Matrix.rotateM(rotMatrix, 0, -90f, 1f, 0f, 0f)
        val newMatrix = FloatArray(16)
        Matrix.multiplyMM(newMatrix, 0, currentMatrix, 0, rotMatrix, 0)
        transformManager.setTransform(transformInstance, newMatrix)
    }

    fun testDown90() {
        val transformManager = modelViewer.engine.transformManager
        val transformInstance = transformManager.getInstance(modelViewer.asset?.root ?: 0)
        if (transformInstance == 0) return

        val currentMatrix = FloatArray(16)
        transformManager.getTransform(transformInstance, currentMatrix)
        val rotMatrix = FloatArray(16)
        Matrix.setIdentityM(rotMatrix, 0)
        Matrix.rotateM(rotMatrix, 0, 30f, 1f, 0f, 0f)
        val newMatrix = FloatArray(16)
        Matrix.multiplyMM(newMatrix, 0, currentMatrix, 0, rotMatrix, 0)
        transformManager.setTransform(transformInstance, newMatrix)
    }

    private var currentDistanceFactor: Float = 1.0f
    private var currentOffsetY: Float = 0f

    /**
     * 设置模型的位置（距离摄像机的距离，以及 Y 轴偏移）
     * @param distanceFactor 正值表示模型远离摄像机模型显示变小，负值表示模型靠近摄像机模型变大
     * @param offsetY Y 轴偏移量，正值向上移动，负值向下移动
     */
    fun initModelPosition(distanceFactor: Float, offsetY: Float = 0f) {
        currentDistanceFactor = distanceFactor
        currentOffsetY = offsetY
        modelViewer.asset?.root?.let { root ->
            val tm = modelViewer.engine.transformManager
            val instance = tm.getInstance(root)
            if (instance == 0) return

            modelViewer.transformToUnitCube()

            val modelMatrix = FloatArray(16)
            tm.getTransform(instance, modelMatrix)
            val modelPos = floatArrayOf(modelMatrix[12], modelMatrix[13], modelMatrix[14])

            val eye = FloatArray(3)
            val target = FloatArray(3)
            val upward = FloatArray(3)
            cameraManipulator.getLookAt(eye, target, upward)

            val dirX = modelPos[0] - eye[0]
            val dirY = modelPos[1] - eye[1]
            val dirZ = modelPos[2] - eye[2]

            val length = sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ)
            val (nx, ny, nz) = if (length < 0.0001f) Triple(0f, 0f, -1f)
            else Triple(dirX / length, dirY / length, dirZ / length)

            val baseDistance = 2f * distanceFactor
            modelMatrix[12] = modelPos[0] + nx * baseDistance
            modelMatrix[13] = modelPos[1] + ny * baseDistance
            modelMatrix[14] = modelPos[2] + nz * baseDistance

            // 应用 Y 轴偏移（世界坐标系）
            modelMatrix[13] += offsetY

            tm.setTransform(instance, modelMatrix)
        }
    }

    // 自动旋转
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
        modelViewer.asset?.root?.let { root ->
            val tm = modelViewer.engine.transformManager
            val instance = tm.getInstance(root)
            if (instance == 0) return

            autoRotateAngle = (autoRotateAngle + autoRotateSpeed * dt) % 360f
            if (autoRotateAngle < 0) autoRotateAngle += 360f

            val currentMatrix = FloatArray(16)
            tm.getTransform(instance, currentMatrix)
            val posX = currentMatrix[12]
            val posY = currentMatrix[13]
            val posZ = currentMatrix[14]

            val scaleX = sqrt(
                currentMatrix[0] * currentMatrix[0] +
                        currentMatrix[1] * currentMatrix[1] +
                        currentMatrix[2] * currentMatrix[2]
            )
            val scaleY = sqrt(
                currentMatrix[4] * currentMatrix[4] +
                        currentMatrix[5] * currentMatrix[5] +
                        currentMatrix[6] * currentMatrix[6]
            )
            val scaleZ = sqrt(
                currentMatrix[8] * currentMatrix[8] +
                        currentMatrix[9] * currentMatrix[9] +
                        currentMatrix[10] * currentMatrix[10]
            )

            val rotationMatrix = FloatArray(16)
            Matrix.setIdentityM(rotationMatrix, 0)
            val rad = Math.toRadians(autoRotateAngle.toDouble()).toFloat()
            val cos = kotlin.math.cos(rad)
            val sin = kotlin.math.sin(rad)

            rotationMatrix[0] = cos * scaleX
            rotationMatrix[2] = sin * scaleZ
            rotationMatrix[4] = 0f
            rotationMatrix[5] = scaleY
            rotationMatrix[6] = 0f
            rotationMatrix[8] = -sin * scaleX
            rotationMatrix[10] = cos * scaleZ
            rotationMatrix[1] = 0f
            rotationMatrix[3] = 0f
            rotationMatrix[7] = 0f
            rotationMatrix[9] = 0f
            rotationMatrix[11] = 0f
            rotationMatrix[15] = 1f
            rotationMatrix[12] = posX
            rotationMatrix[13] = posY
            rotationMatrix[14] = posZ

            tm.setTransform(instance, rotationMatrix)
        }
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

    /**
     * 恢复模型的初始状态：
     * - 位置重置为 initModelPosition 时的位置（距离摄像机 1.5 倍标准距离）
     * - 缩放重置为 transformToUnitCube 后的归一化大小
     * - 旋转角度清零（模型朝向初始方向）
     * - 停止自动旋转，重置自转角度累积
     */
    fun resetModelTransform() {
        modelViewer.asset?.root?.let { root ->
            val tm = modelViewer.engine.transformManager
            val instance = tm.getInstance(root)
            if (instance == 0) return

            // 1. 重置变换矩阵为单位矩阵（清除所有旋转、缩放、位移）
            val identity = FloatArray(16)
            android.opengl.Matrix.setIdentityM(identity, 0)
            tm.setTransform(instance, identity)
        }

        // 2. 重新归一化模型尺寸（确保模型大小为单位立方体）
        modelViewer.transformToUnitCube()

        // 3. 重新设置模型位置（距离摄像机 1.5 倍标准距离）
        initModelPosition(currentDistanceFactor,currentOffsetY)

        // 4. 重置用户旋转角度累积
        userRotationAngle = 0f
        userPitchAngle = 0f

        // 5. 重置自动旋转状态
        autoRotateAngle = 0f
        setAutoRotate(true)
    }
}