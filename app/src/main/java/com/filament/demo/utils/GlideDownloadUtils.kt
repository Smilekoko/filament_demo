package com.filament.demo.utils

import android.content.Context
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GlideDownloadUtils {

    // 使用协程挂起函数
    suspend fun downloadFileAsBytes(context: Context, url: String): Result<ByteArray> {
        return withContext(Dispatchers.IO) {
            try {
                // 下载文件到缓存
                val future = Glide.with(context)
                    .downloadOnly()
                    .load(url)
                    .submit()

                val file = future.get()

                // 将文件读取为字节数组
                val bytes = file.readBytes()

                // 可选：清理文件缓存
//                file.delete()

                Result.success(bytes)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

}