package com.ai.assistance.operit.integrations.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.ai.assistance.operit.integrations.externalchat.ExternalChatRequest
import com.ai.assistance.operit.integrations.externalchat.ExternalChatRequestExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Opt-in cloud command consumer. It runs only after device credentials are
 * provisioned in private app preferences.
 */
class DeviceCommandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()
    private lateinit var executor: ExternalChatRequestExecutor

    override fun onCreate() {
        super.onCreate()
        executor = ExternalChatRequestExecutor(applicationContext)
        startForeground(NOTIFICATION_ID, notification())
        scope.launch { pollLoop() }
    }

    private fun notification(): android.app.Notification {
        val channelId = "aion_device_executor"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(channelId, "手机执行器", NotificationManager.IMPORTANCE_LOW))
        }
        return android.app.Notification.Builder(this, channelId)
            .setContentTitle("手机执行器已运行")
            .setContentText("等待云端命令")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private suspend fun pollLoop() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        while (scope.isActive) {
            val base = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL)?.trim()?.trimEnd('/')
            val deviceId = prefs.getString(KEY_DEVICE_ID, "")?.trim().orEmpty()
            val token = prefs.getString(KEY_DEVICE_TOKEN, "")?.trim().orEmpty()
            if (!base.isNullOrBlank() && deviceId.isNotBlank() && token.isNotBlank()) {
                runCatching { claimAndExecute(base, deviceId, token) }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun claimAndExecute(base: String, deviceId: String, token: String) {
        val request = Request.Builder()
            .url("$base/api/device/commands/claim?limit=3")
            .header("X-Aion-Device-Id", deviceId)
            .header("X-Aion-Device-Token", token)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            val payload = JSONObject(response.body?.string().orEmpty())
            val commands = payload.optJSONArray("commands") ?: JSONArray()
            for (index in 0 until commands.length()) {
                executeOne(base, deviceId, token, commands.optJSONObject(index) ?: continue)
            }
        }
    }

    private fun executeOne(base: String, deviceId: String, token: String, command: JSONObject) {
        val commandId = command.optString("command_id")
        if (commandId.isBlank()) return
        val name = command.optString("command_name")
        val arguments = command.optJSONObject("arguments") ?: JSONObject()
        var success = false
        var result: JSONObject? = null
        var error = ""
        try {
            when (name) {
                "device_info" -> {
                    result = DeviceInfo.read(this)
                    success = true
                }
                "external_chat" -> {
                    val message = arguments.optString("message").trim()
                    require(message.isNotBlank()) { "message is required" }
                    val request = ExternalChatRequest(
                        requestId = command.optString("request_id"),
                        message = message,
                        chatId = arguments.optString("chat_id").takeIf { it.isNotBlank() },
                        returnToolStatus = arguments.optBoolean("return_tool_status", true),
                        timeoutMs = arguments.optLong("timeout_ms", -1L),
                    )
                    val response = kotlinx.coroutines.runBlocking { executor.execute(request) }
                    result = JSONObject().apply {
                        put("success", response.success)
                        put("chat_id", response.chatId ?: JSONObject.NULL)
                        put("ai_response", response.aiResponse ?: JSONObject.NULL)
                        put("error", response.error ?: JSONObject.NULL)
                    }
                    success = response.success
                    error = response.error.orEmpty()
                }
                else -> error = "command_not_allowlisted"
            }
        } catch (throwable: Throwable) {
            error = throwable.message ?: "command_failed"
        }
        complete(base, deviceId, token, commandId, success, result, error)
    }

    private fun complete(base: String, deviceId: String, token: String, commandId: String, success: Boolean, result: JSONObject?, error: String) {
        val body = JSONObject().apply {
            put("command_id", commandId)
            put("success", success)
            put("result", result ?: JSONObject.NULL)
            put("error", error.take(2000))
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$base/api/device/commands/complete")
            .header("X-Aion-Device-Id", deviceId)
            .header("X-Aion-Device-Token", token)
            .post(body)
            .build()
        runCatching { client.newCall(request).execute().close() }
    }

    override fun onDestroy() {
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val PREFS = "aion_device_executor"
        private const val KEY_BASE_URL = "cloud_base_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val DEFAULT_BASE_URL = "https://aionhome-cloud.onrender.com"
        private const val POLL_INTERVAL_MS = 15_000L
        private const val NOTIFICATION_ID = 2401
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun configure(context: Context, deviceId: String, token: String, baseUrl: String = DEFAULT_BASE_URL) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_BASE_URL, baseUrl.trimEnd('/'))
                .putString(KEY_DEVICE_ID, deviceId.trim())
                .putString(KEY_DEVICE_TOKEN, token.trim())
                .apply()
        }

        fun start(context: Context) {
            val intent = Intent(context, DeviceCommandService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}

private object DeviceInfo {
    fun read(context: Context): JSONObject {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val level = battery?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it >= 0 }
        return JSONObject().apply {
            put("sdk", android.os.Build.VERSION.SDK_INT)
            put("release", android.os.Build.VERSION.RELEASE ?: "")
            put("manufacturer", android.os.Build.MANUFACTURER ?: "")
            put("model", android.os.Build.MODEL ?: "")
            put("battery_percent", level ?: JSONObject.NULL)
        }
    }
}
