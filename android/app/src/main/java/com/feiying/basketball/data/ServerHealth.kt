package com.feiying.basketball.data

import com.feiying.basketball.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ServerHealth {
    suspend fun check(baseUrl: String = BuildConfig.SERVER_BASE_URL): String =
        withContext(Dispatchers.IO) {
            val connection = (URL("$baseUrl/api/v1/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 3_000
                requestMethod = "GET"
            }
            try {
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "服务端在线"
                } else {
                    "服务端返回 HTTP ${connection.responseCode}"
                }
            } catch (error: Exception) {
                "无法连接服务端：${error.message ?: error.javaClass.simpleName}"
            } finally {
                connection.disconnect()
            }
        }
}
