/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this文件 except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.filament.demo

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.util.Log
import android.view.*
import android.view.GestureDetector
import android.widget.TextView
import android.widget.Toast
import com.google.android.filament.EntityManager
import com.google.android.filament.Fence
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.Renderer
import com.google.android.filament.Skybox
import com.google.android.filament.View
import com.google.android.filament.View.OnPickCallback
import com.google.android.filament.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.URI
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

class MainActivity : Activity() {

    companion object {
        // 初始化工具库，这会加载gltfio和Filament核心库
        init {
            Utils.init()
        }

        private const val TAG = "gltf-viewer"
        private const val STATIC_MODEL_TAG = "use-static-model"  // 静态模型标识
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var choreographer: Choreographer
    private val frameScheduler = FrameCallback()
    private lateinit var modelViewer: ModelViewer
    private lateinit var titlebarHint: TextView
    private val doubleTapListener = DoubleTapListener()
    private val singleTapListener = SingleTapListener()
    private lateinit var doubleTapDetector: GestureDetector
    private lateinit var singleTapDetector: GestureDetector
    private var remoteServer: RemoteServer? = null          // 远程服务器，用于接收模型文件
    private var statusToast: Toast? = null                  // 状态提示Toast
    private var statusText: String? = null                  // 状态文本
    private var latestDownload: String? = null              // 最近下载的文件名
    private val automation = AutomationEngine()             // 自动化引擎，用于处理设置
    private var loadStartTime = 0L                          // 模型加载开始时间
    private var loadStartFence: Fence? = null               // 用于同步模型加载完成的Fence
    private val viewerContent = AutomationEngine.ViewerContent() // 查看器内容容器
    private var useStaticModel = true                      // 是否使用静态模型

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.simple_layout)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)  // 保持屏幕常亮

//        titlebarHint = findViewById(R.id.user_hint)          // 标题栏提示文本
        surfaceView = findViewById(R.id.main_sv)             // 主SurfaceView
        choreographer = Choreographer.getInstance()

        doubleTapDetector = GestureDetector(applicationContext, doubleTapListener)
        singleTapDetector = GestureDetector(applicationContext, singleTapListener)

        // 检查是否从Intent中获取了使用静态模型的参数
//        val intent: Intent = intent
//        val bundle: Bundle? = intent.extras
//        bundle?.let {
//            useStaticModel = it.getBoolean(STATIC_MODEL_TAG, false)
//        }

        modelViewer = ModelViewer(surfaceView)               // 初始化模型查看器
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


        // 设置触摸事件监听器
        surfaceView.setOnTouchListener { _, event ->
            modelViewer.onTouchEvent(event)                 // 传递触摸事件给模型查看器
            doubleTapDetector.onTouchEvent(event)           // 检测双击
            singleTapDetector.onTouchEvent(event)           // 检测单击
            true
        }

        createDefaultRenderables()                           // 创建默认渲染对象（加载默认模型）
//        createIndirectLight()                               // 创建间接光照（默认被注释）

//        setStatusText("要加载新模型，请在主机上访问上述URL。")

        val view = modelViewer.view

        /*
         * 注意：以下设置在被连接到远程UI时会被覆盖。
         */

        // 在移动设备上，最好使用较低质量的色彩缓冲区
//        view.renderQuality = view.renderQuality.apply {
//            hdrColorBuffer = View.QualityLevel.MEDIUM
//        }

        // 动态分辨率通常有很大帮助
//        view.dynamicResolutionOptions = view.dynamicResolutionOptions.apply {
//            enabled = true
//            quality = View.QualityLevel.MEDIUM
//        }

        // 启用MSAA（多重采样抗锯齿）以配合MEDIUM动态分辨率
//        view.multiSampleAntiAliasingOptions = view.multiSampleAntiAliasingOptions.apply {
//            enabled = true
//        }

        // FXAA效果不错且开销小
//        view.antiAliasing = View.AntiAliasing.FXAA

        // 环境光遮蔽（AO）是性价比很高的效果，能显著提升质量
//        view.ambientOcclusionOptions = view.ambientOcclusionOptions.apply {
//            enabled = true
//        }

        // 泛光（Bloom）效果开销较大但能增加真实感
//        view.bloomOptions = view.bloomOptions.apply {
//            enabled = true
//        }

//        remoteServer = RemoteServer(8082)                   // 启动远程服务器，监听8082端口
    }


    /**
     * 创建默认渲染对象（加载默认模型）
     */
    private fun createDefaultRenderables() {
        // 有时将默认模型设置为静态模型很有用。可以通过adb启动应用时启用静态模型：
        // `adb shell am start -n com.google.android.filament.gltf/.MainActivity --ez "use-static-model" true`
        val modelPath = if (useStaticModel) {
//            "models/helmet.glb"                            // 静态头盔模型
            "models/wawa.glb"                            // 娃娃模型
        } else {
            "models/scene.gltf"                            // 默认场景模型
        }

        // 从assets读取模型文件到ByteBuffer
        val buffer = assets.open(modelPath).use { input ->
            val bytes = ByteArray(input.available())
            input.read(bytes)
            ByteBuffer.wrap(bytes)
        }

        modelViewer.loadModelGlb(buffer)
        // 异步加载GLTF模型，并指定资源加载器
//        modelViewer.loadModelGltfAsync(buffer)
//        { uri ->
//            ByteBuffer.wrap(byteArrayOf())
//            readCompressedAsset("models/$uri")
//        }
        updateRootTransform()                               // 更新根变换

        surfaceView.holder.setFormat(PixelFormat.TRANSPARENT)
        surfaceView.setZOrderOnTop(true)
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

//    /**
//     * 创建间接光照和环境天空盒
//     */
//    private fun createIndirectLight() {
//        val engine = modelViewer.engine
//        val scene = modelViewer.scene
//        val ibl = "default_env"                            // 默认环境贴图名称
//        // 加载IBL（基于图像的照明）贴图
//        readCompressedAsset("envs/$ibl/${ibl}_ibl.ktx").let {
//            val bundle = KTX1Loader.createIndirectLight(engine, it)
//            scene.indirectLight = bundle.indirectLight      // 设置场景间接光照
//            modelViewer.indirectLightCubemap = bundle.cubemap
//            scene.indirectLight!!.intensity = 30_000.0f     // 设置光照强度
//            viewerContent.indirectLight = modelViewer.scene.indirectLight
//        }
//        // 加载天空盒贴图
//        readCompressedAsset("envs/$ibl/${ibl}_skybox.ktx").let {
//            val bundle = KTX1Loader.createSkybox(engine, it)
//            scene.skybox = bundle.skybox                    // 设置场景天空盒
//            modelViewer.skyboxCubemap = bundle.cubemap
//        }
//    }

    /**
     * 从assets读取压缩资源到ByteBuffer
     */
//    private fun readCompressedAsset(assetName: String): ByteBuffer {
//        val input = assets.open(assetName)
//        val bytes = ByteArray(input.available())
//        input.read(bytes)
//        return ByteBuffer.wrap(bytes)
//    }

    /**
     * 清除状态提示文本
     */
//    private fun clearStatusText() {
//        statusToast?.let {
//            it.cancel()
//            statusText = null
//        }
//    }

    /**
     * 设置状态提示文本（显示Toast）
     */
//    private fun setStatusText(text: String) {
//        runOnUiThread {
//            if (statusToast == null || statusText != text) {
//                statusText = text
//                statusToast = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT)
//                statusToast!!.show()
//            }
//        }
//    }

    /**
     * 加载GLB格式模型（协程）
     */
//    private suspend fun loadGlb(message: RemoteServer.ReceivedMessage) {
//        withContext(Dispatchers.Main) {
//            modelViewer.destroyModel()                      // 销毁当前模型
//            modelViewer.loadModelGlb(message.buffer)        // 加载新的GLB模型
//            updateRootTransform()                           // 更新根变换
//            loadStartTime = System.nanoTime()               // 记录加载开始时间
//            loadStartFence = modelViewer.engine.createFence() // 创建同步Fence
//        }
//    }

    /**
     * 加载HDR环境贴图（协程）
     */
//    private suspend fun loadHdr(message: RemoteServer.ReceivedMessage) {
//        withContext(Dispatchers.Main) {
//            val engine = modelViewer.engine
//            // 从HDR文件创建纹理
//            val equirect = HDRLoader.createTexture(engine, message.buffer)
//            if (equirect == null) {
//                setStatusText("无法解码HDR文件。")
//            } else {
//                setStatusText("成功解码HDR文件。")
//
//                // 创建IBL预过滤上下文
//                val context = IBLPrefilterContext(engine)
//                val equirectToCubemap = IBLPrefilterContext.EquirectangularToCubemap(context)
//                // 将等距柱状投影转换为立方体贴图
//                val skyboxTexture = equirectToCubemap.run(equirect)!!
//                engine.destroyTexture(equirect)             // 销毁原始纹理
//
//                // 创建镜面反射过滤器
//                val specularFilter = IBLPrefilterContext.SpecularFilter(context)
//                val reflections = specularFilter.run(skyboxTexture)
//
//                // 创建间接光照
//                val ibl = IndirectLight.Builder()
//                    .reflections(reflections)
//                    .intensity(30000.0f)
//                    .build(engine)
//
//                // 创建天空盒
//                val sky = Skybox.Builder().environment(skyboxTexture).build(engine)
//
//                // 清理资源
//                specularFilter.destroy()
//                equirectToCubemap.destroy()
//                context.destroy()
//
//                // 销毁旧的IBL和天空盒
//                engine.destroyIndirectLight(modelViewer.scene.indirectLight!!)
//                engine.destroySkybox(modelViewer.scene.skybox!!)
//
//                // 设置新的天空盒和间接光照
//                modelViewer.scene.skybox = sky
//                modelViewer.scene.indirectLight = ibl
//                viewerContent.indirectLight = ibl
//            }
//        }
//    }

    /**
     * 加载ZIP压缩包（协程）
     */
//    private suspend fun loadZip(message: RemoteServer.ReceivedMessage) {
//        // 为了缓解内存压力，在解压ZIP前先移除旧模型
//        withContext(Dispatchers.Main) {
//            modelViewer.destroyModel()
//        }
//
//        // 大ZIP文件应先写入文件，防止内存溢出
//        val (zipStream, zipFile) = withContext(Dispatchers.IO) {
//            val file = File.createTempFile("incoming", "zip", cacheDir)
//            val raf = RandomAccessFile(file, "rw")
//            raf.channel.write(message.buffer)               // 将ByteBuffer写入临时文件
//            message.buffer = null                           // 清空原始buffer
//            raf.seek(0)
//            Pair(FileInputStream(file), file)
//        }
//
//        // 逐个解压ZIP中的资源
//        var gltfPath: String? = null
//        var outOfMemory: String? = null
//        val pathToBufferMapping = withContext(Dispatchers.IO) {
//            val deflater = ZipInputStream(zipStream)
//            val mapping = HashMap<String, Buffer>()
//            while (true) {
//                val entry = deflater.nextEntry ?: break     // 遍历ZIP条目
//                if (entry.isDirectory) continue
//
//                // 忽略ZIP文件中常见的垃圾文件（非必需，但作为优化）
//                if (entry.name.startsWith("__MACOSX")) continue
//                if (entry.name.startsWith(".DS_Store")) continue
//
//                val uri = entry.name
//                val byteArray: ByteArray? = try {
//                    deflater.readBytes()                    // 读取条目数据
//                } catch (e: OutOfMemoryError) {
//                    outOfMemory = uri
//                    break
//                }
//                Log.i(TAG, "从 $uri 解压了 ${byteArray!!.size} 字节")
//                val buffer = ByteBuffer.wrap(byteArray)
//                mapping[uri] = buffer
//                if (uri.endsWith(".gltf") || uri.endsWith(".glb")) {
//                    gltfPath = uri                          // 记录主模型文件路径
//                }
//            }
//            mapping
//        }
//
//        zipFile.delete()                                    // 删除临时ZIP文件
//
//        if (gltfPath == null) {
//            setStatusText("在ZIP中找不到.gltf或.glb文件。")
//            return
//        }
//
//        if (outOfMemory != null) {
//            setStatusText("解压 $outOfMemory 时内存不足")
//            return
//        }
//
//        val gltfBuffer = pathToBufferMapping[gltfPath]!!
//
//        // 确定资源路径前缀（相对路径解析）
//        var prefix = URI(gltfPath!!).resolve(".")
//
//        withContext(Dispatchers.Main) {
//            if (gltfPath!!.endsWith(".glb")) {
//                modelViewer.loadModelGlb(gltfBuffer)       // 加载GLB格式
//            } else {
//                // 加载GLTF格式，并提供资源加载回调
//                modelViewer.loadModelGltf(gltfBuffer) { uri ->
//                    val path = prefix.resolve(uri).toString()
//                    if (!pathToBufferMapping.contains(path)) {
//                        Log.e(
//                            TAG,
//                            "在ZIP中找不到 '$uri'，使用前缀 '$prefix' 和基础路径 '${gltfPath!!}'"
//                        )
//                        setStatusText("ZIP缺少 $path")
//                    }
//                    pathToBufferMapping[path]              // 返回对应资源的Buffer
//                }
//            }
//            updateRootTransform()                           // 更新根变换
//            loadStartTime = System.nanoTime()               // 记录加载开始时间
//            loadStartFence = modelViewer.engine.createFence() // 创建同步Fence
//        }
//    }

    override fun onResume() {
        super.onResume()
        choreographer.postFrameCallback(frameScheduler)     // 注册帧回调
    }

    override fun onPause() {
        super.onPause()
        choreographer.removeFrameCallback(frameScheduler)   // 移除帧回调
    }

    override fun onDestroy() {
        super.onDestroy()
        choreographer.removeFrameCallback(frameScheduler)
//        remoteServer?.close()                               // 关闭远程服务器
    }

    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }

    /**
     * 加载模型数据（根据文件类型分发）
     */
//    fun loadModelData(message: RemoteServer.ReceivedMessage) {
//        Log.i(TAG, "已下载模型 ${message.label} (${message.buffer.capacity()} 字节)")
//        clearStatusText()
//        titlebarHint.text = message.label                   // 在标题栏显示文件名
//        CoroutineScope(Dispatchers.IO).launch {
//            when {
//                message.label.endsWith(".zip") -> loadZip(message)
//                message.label.endsWith(".hdr") -> loadHdr(message)
//                else -> loadGlb(message)
//            }
//        }
//    }

    /**
     * 加载设置（JSON格式）
     */
//    fun loadSettings(message: RemoteServer.ReceivedMessage) {
//        val json = StandardCharsets.UTF_8.decode(message.buffer).toString()
//        viewerContent.assetLights = modelViewer.asset?.lightEntities
//        // 应用自动化设置
//        automation.applySettings(modelViewer.engine, json, viewerContent)
//        modelViewer.view.colorGrading = automation.getColorGrading(modelViewer.engine)
//        modelViewer.cameraFocalLength = automation.viewerOptions.cameraFocalLength
//        modelViewer.cameraNear = automation.viewerOptions.cameraNear
//        modelViewer.cameraFar = automation.viewerOptions.cameraFar
//        updateRootTransform()                               // 更新根变换
//    }

    /**
     * 更新根变换（根据自动缩放设置）
     */
    private fun updateRootTransform() {
//        if (automation.viewerOptions.autoScaleEnabled) {
        modelViewer.transformToUnitCube(Float3(0.0f, 0.0f, -4.0f))              // 自动缩放到单位立方体
//        } else {
//            modelViewer.clearRootTransform()               // 清除根变换
//        }
    }

    /**
     * 帧回调类，处理每帧的渲染和更新逻辑
     */
    inner class FrameCallback : Choreographer.FrameCallback {
        private val startTime = System.nanoTime()           // 动画开始时间
        override fun doFrame(frameTimeNanos: Long) {
            choreographer.postFrameCallback(this)           // 注册下一帧回调

            // 检查模型加载是否完成
//            loadStartFence?.let {
//                if (it.wait(Fence.Mode.FLUSH, 0) == Fence.FenceStatus.CONDITION_SATISFIED) {
//                    val end = System.nanoTime()
//                    val total = (end - loadStartTime) / 1_000_000
//                    Log.i(TAG, "Filament后端加载模型几何体耗时 $total 毫秒。")
//                    modelViewer.engine.destroyFence(it)
//                    loadStartFence = null
//
//                    // 编译所有材质，优化渲染性能
//                    val materials = mutableSetOf<Material>()
//                    val rcm = modelViewer.engine.renderableManager
//                    modelViewer.scene.forEach {
//                        val entity = it
//                        if (rcm.hasComponent(entity)) {
//                            val ri = rcm.getInstance(entity)
//                            val c = rcm.getPrimitiveCount(ri)
//                            for (i in 0 until c) {
//                                val mi = rcm.getMaterialInstanceAt(ri, i)
//                                val ma = mi.material
//                                materials.add(ma)
//                            }
//                        }
//                    }
//                    // 分优先级编译材质变体
//                    materials.forEach {
//                        it.compile(
//                            Material.CompilerPriorityQueue.HIGH,
//                            Material.UserVariantFilterBit.DIRECTIONAL_LIGHTING or
//                                    Material.UserVariantFilterBit.DYNAMIC_LIGHTING or
//                                    Material.UserVariantFilterBit.SHADOW_RECEIVER,
//                            null, null
//                        )
//                        it.compile(
//                            Material.CompilerPriorityQueue.LOW,
//                            Material.UserVariantFilterBit.FOG or
//                                    Material.UserVariantFilterBit.SKINNING or
//                                    Material.UserVariantFilterBit.SSR or
//                                    Material.UserVariantFilterBit.VSM,
//                            null, null
//                        )
//                    }
//                }
//            }

            // 更新动画
//            modelViewer.animator?.apply {
//                if (animationCount > 0) {
//                    // 计算经过的时间并应用动画
//                    val elapsedTimeSeconds = (frameTimeNanos - startTime).toDouble() / 1_000_000_000
//                    applyAnimation(0, elapsedTimeSeconds.toFloat())
//                }
//                updateBoneMatrices()                       // 更新骨骼矩阵
//            }
            modelViewer.render(frameTimeNanos)              // 渲染当前帧
            updateLightFollowCamera()//光照跟随相机

            // 检查是否有新下载，如果有则显示提示
//            val currentDownload = remoteServer?.peekIncomingLabel()
//            if (RemoteServer.isBinary(currentDownload) && currentDownload != latestDownload) {
//                latestDownload = currentDownload
//                Log.i(TAG, "正在下载 $currentDownload")
//                setStatusText("正在下载 $currentDownload")
//            }

            // 检查是否从客户端接收到了完整的消息
//            val message = remoteServer?.acquireReceivedMessage()
//            if (message != null) {
//                if (message.label == latestDownload) {
//                    latestDownload = null
//                }
//                if (RemoteServer.isJson(message.label)) {
//                    loadSettings(message)                   // 加载JSON设置
//                } else {
//                    loadModelData(message)                  // 加载模型数据
//                }
//            }
        }
    }

    /**
     * 双击监听器（测试用途：重新加载默认模型）
     */
    inner class DoubleTapListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            Toast.makeText(this@MainActivity, "双击", Toast.LENGTH_SHORT).show()
//            modelViewer.destroyModel()                      // 销毁当前模型
//            createDefaultRenderables()                      // 重新创建默认渲染对象
            return super.onDoubleTap(e)
        }
    }

    /**
     * 单击监听器（测试用途：拾取物体）
     */
    inner class SingleTapListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            // 执行拾取操作，获取被点击的渲染对象信息
//            modelViewer.view.pick(
//                event.x.toInt(),
//                surfaceView.height - event.y.toInt(),       // 注意Y坐标翻转
//                surfaceView.handler,
//                {
//                    val name = modelViewer.asset!!.getName(it.renderable)
//                    Log.v("Filament", "拾取了 ${it.renderable}: " + name)
//                },
//            )
            Toast.makeText(this@MainActivity, "单击", Toast.LENGTH_SHORT).show()
            return super.onSingleTapUp(event)
        }
    }
}