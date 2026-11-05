package io.github.norlive.remotecontrol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 调试链路：把与蓝牙完全相同的 20 字节帧交给电脑上的桥接器，由桥接器经 SWD 写进 STM32 的 g_rc。
 *
 * 这里只替换传输层：摇杆、死区、滑块、按钮通道、使能与急停仍然走 App 现有的逻辑，
 * 所以 App 侧的操作手感与蓝牙模式完全一致。
 */
internal class TuneLink(
    private val baseUrl: String,
    token: String,
    private val onNote: (String) -> Unit,
) : Bt630Session.Link {
    private val token = token.trim()
    private var frames = 0
    private var lastNoteAt = 0L

    override suspend fun open() = withContext(Dispatchers.IO) {
        val health = call("GET", "/api/health", null)
        if (!health.optBoolean("write_enabled", false))
            throw Bt630Failure("桥接器处于只读模式，请用 --allow-write 启动", retryable = false)
        val symbol = health.optString("symbol", "g_rc")
        val address = health.optLong("address", 0L)
        onNote("$symbol @0x${address.toString(16).uppercase().padStart(8, '0')} · 链路正常")
    }

    override suspend fun send(frame: ByteArray) = withContext(Dispatchers.IO) {
        val result = call("POST", "/api/frame", frame)
        frames++
        val now = System.currentTimeMillis()
        if (now - lastNoteAt < 500) return@withContext
        lastNoteAt = now
        onNote(
            if (result.optBoolean("verified", false)) "已写入 $frames 帧 · 回读一致"
            else "写入未生效：${result.optString("error", "读回不一致，检查 D-Cache")}"
        )
    }

    override fun close() {
        // 尽力补一帧急停；桥接器自己的看门狗也会在断流后写安全值。
        runCatching {
            val neutral = ControlProtocol.encode(ControlState(stopped = true), 0)
            val request = request("POST", "/api/frame").post(neutral.toRequestBody(Octet)).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
        }
    }

    private fun call(method: String, path: String, body: ByteArray?): JSONObject {
        val builder = request(method, path)
        if (body != null) builder.post(body.toRequestBody(Octet))
        return client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            when {
                response.code == 401 -> throw Bt630Failure("令牌错误：与电脑上打印的令牌不一致", retryable = false)
                response.code == 403 -> throw Bt630Failure(json.optString("error", "桥接器处于只读模式"), retryable = false)
                !response.isSuccessful -> throw Bt630Failure(json.optString("error", "HTTP ${response.code}"), retryable = true)
            }
            json
        }
    }

    private fun request(method: String, path: String): Request.Builder =
        Request.Builder().url(baseUrl.trimEnd('/') + path).apply { if (token.isNotEmpty()) header("X-Token", token) }

    private companion object {
        val Octet = "application/octet-stream".toMediaType()

        // 整条 App 共用一个连接池：断线重连时不会每次新建线程池和 socket。
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(800, TimeUnit.MILLISECONDS)
            .readTimeout(800, TimeUnit.MILLISECONDS)
            .writeTimeout(800, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}