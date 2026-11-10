package com.chronocapsule.net

import android.content.Context
import android.util.Log
import com.chronocapsule.contract.ScreenContract
import org.json.JSONObject
import java.io.File

/**
 * 离线队列的 Android 侧封装：负责把 payload 序列化/反序列化，并决定
 * 一条缓存在服务端答复之后该保留还是丢弃。
 */
object PendingUploads {

    private const val TAG = "PendingUploads"

    fun dir(context: Context): File = File(context.filesDir, "upload_queue")

    /** 网络不可达时调用 */
    fun enqueue(context: Context, payload: ScreenContract.Payload) {
        val file = UploadQueue.enqueue(dir(context), payload.eventId, payload.toJsonString())
        if (file != null) {
            Log.i(TAG, "云端不可达，已缓存 ${payload.eventId}，待补传 ${UploadQueue.pendingCount(dir(context))} 屏")
        } else {
            Log.w(TAG, "缓存失败：${payload.eventId}")
        }
    }

    /**
     * 补传所有缓存屏。必须在后台线程调用。
     * @return 本轮处理掉（成功或丢弃）的屏数
     */
    fun drain(context: Context): Int {
        val dir = dir(context)
        if (UploadQueue.pendingCount(dir) == 0) return 0

        val baseUrls = CloudConfig.baseUrls(context)
        val handled = UploadQueue.drain(dir) { json ->
            val payload = runCatching {
                ScreenContract.Payload.fromJson(JSONObject(json))
            }.getOrNull()

            if (payload == null) {
                Log.w(TAG, "缓存内容损坏，丢弃")
                return@drain UploadQueue.DrainAction.DISCARD
            }

            val result = CloudClient.uploadScreen(payload, baseUrls)
            when {
                result.ok -> {
                    Log.i(TAG, "补传成功 ${payload.eventId} → ${result.screenId}")
                    UploadQueue.DrainAction.DONE
                }

                result.httpCode == -1 -> {
                    Log.w(TAG, "补传失败（网络未恢复），保留 ${payload.eventId}")
                    UploadQueue.DrainAction.RETRY_LATER
                }

                else -> {
                    Log.w(TAG, "补传被服务端拒绝 HTTP ${result.httpCode}，丢弃 ${payload.eventId}")
                    UploadQueue.DrainAction.DISCARD
                }
            }
        }

        if (handled > 0) {
            Log.i(TAG, "本轮补传处理 $handled 屏，剩余 ${UploadQueue.pendingCount(dir)} 屏")
        }
        return handled
    }
}
