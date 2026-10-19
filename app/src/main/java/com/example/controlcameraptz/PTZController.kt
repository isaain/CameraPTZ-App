package com.robotx30.controlcameraptz

import com.burgstaller.okhttp.AuthenticationCacheInterceptor
import com.burgstaller.okhttp.CachingAuthenticatorDecorator
import com.burgstaller.okhttp.digest.CachingAuthenticator
import com.burgstaller.okhttp.digest.Credentials
import com.burgstaller.okhttp.digest.DigestAuthenticator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

class PTZController(
    var cameraIp: String = "192.168.1.108",
    var username: String = "admin",
    var password: String = "admin"
) {

    private fun getClient(): OkHttpClient {
        val digestAuth = DigestAuthenticator(Credentials(username, password))
        val authCache = ConcurrentHashMap<String, CachingAuthenticator>()
        val cachingDecorator = CachingAuthenticatorDecorator(digestAuth, authCache)

        val combinedAuth = object : Authenticator {
            override fun authenticate(route: Route?, response: Response): Request? {
                val header = response.header("WWW-Authenticate") ?: ""
                return if (header.contains("Digest", ignoreCase = true)) {
                    cachingDecorator.authenticate(route, response)
                } else {
                    val basicCred = okhttp3.Credentials.basic(username, password)
                    response.request.newBuilder().header("Authorization", basicCred).build()
                }
            }
        }

        return OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .authenticator(combinedAuth)
            .addInterceptor(AuthenticationCacheInterceptor(authCache))
            .build()
    }

    fun getCleanIp(): String {
        return try {
            var clean = cameraIp.trim()
            if (clean.contains("@")) {
                clean = clean.split("@")[1]
            }
            clean = clean.replace("rtsp://", "").replace("http://", "")
            clean.split(":")[0].split("/")[0]
        } catch (e: Exception) {
            "192.168.1.108"
        }
    }

    suspend fun startMove(code: String, speed: Int = 5): String {
        return withContext(Dispatchers.IO) {
            try {
                val ip = getCleanIp()
                // Estándar Dahua PTZ CGI: arg1=0, arg2=speed, arg3=0, arg4=0
                val url1 = "http://$ip/cgi-bin/ptz.cgi?action=start&channel=1&code=$code&arg1=0&arg2=$speed&arg3=0&arg4=0"
                val request1 = Request.Builder().url(url1).build()

                getClient().newCall(request1).execute().use { response ->
                    if (response.isSuccessful) {
                        return@withContext "OK"
                    }
                    if (response.code != 404 && response.code != 400) {
                        return@withContext "HTTP ${response.code}"
                    }
                }

                // Fallback sin canal explícito
                val url2 = "http://$ip/cgi-bin/ptz.cgi?action=start&code=$code&arg1=0&arg2=$speed&arg3=0&arg4=0"
                val request2 = Request.Builder().url(url2).build()
                getClient().newCall(request2).execute().use { response ->
                    if (response.isSuccessful) {
                        "OK"
                    } else {
                        "HTTP ${response.code}"
                    }
                }
            } catch (e: Exception) {
                e.message ?: "Error de conexión"
            }
        }
    }

    suspend fun stopMove(code: String = "Up"): String {
        return withContext(Dispatchers.IO) {
            try {
                val ip = getCleanIp()
                val url1 = "http://$ip/cgi-bin/ptz.cgi?action=stop&channel=1&code=$code&arg1=0&arg2=0&arg3=0&arg4=0"
                val request1 = Request.Builder().url(url1).build()

                getClient().newCall(request1).execute().use { response ->
                    if (response.isSuccessful) {
                        return@withContext "OK"
                    }
                }

                val url2 = "http://$ip/cgi-bin/ptz.cgi?action=stop&code=$code&arg1=0&arg2=0&arg3=0&arg4=0"
                val request2 = Request.Builder().url(url2).build()
                getClient().newCall(request2).execute().use { response ->
                    if (response.isSuccessful) {
                        "OK"
                    } else {
                        "HTTP ${response.code}"
                    }
                }
            } catch (e: Exception) {
                e.message ?: "Error de conexión"
            }
        }
    }

    suspend fun sendCommand(code: String, speed: Int = 5, durationMs: Long = 500): String {
        return withContext(Dispatchers.IO) {
            val startRes = startMove(code, speed)
            if (startRes != "OK") {
                return@withContext startRes
            }
            delay(durationMs.milliseconds)
            stopMove(code)
        }
    }

    suspend fun takeSnapshot(channel: Int): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val ip = getCleanIp()
                val url = "http://$ip/cgi-bin/snapshot.cgi?channel=$channel"
                val request = Request.Builder().url(url).build()

                getClient().newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.bytes()
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
