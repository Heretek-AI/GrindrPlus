package com.grindrplus.core.http

import com.grindrplus.core.Logger
import com.grindrplus.core.LogSource
import de.robv.android.xposed.XposedBridge
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.TimeZone
import okhttp3.Request.Builder
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/**
 * OkHttp interceptor that injects the auth + telemetry headers Grindr's
 * backend expects on every request.
 *
 * The interceptor reflects into three live objects from the host app —
 * the [userSession] (provides the JWT + roles), the [userAgent] (provides
 * the User-Agent string), and the [deviceInfo] (provides the device-fp
 * header). Method and field letters change every Grindr release, so the
 * call sites use `// search for '<snippet>'` markers as breadcrumbs.
 *
 * Failures here are *swallowed* with a synthetic [Response] so the hook
 * never crashes the host app on a partial API mismatch — the cost is a
 * failed HTTP request, which is much easier to diagnose than a crash.
 *
 * @param userSession Live user-session object from the host app (e.g.
 *                    `com.grindrapp.android.usersession.<obf>`).
 * @param userAgent   Live user-agent object from the host app.
 * @param deviceInfo  Live device-info object from the host app.
 */
class Interceptor(
    private val userSession: Any,
    private val userAgent: Any,
    private val deviceInfo: Any
) : Interceptor {

    private fun modifyRequest(originalRequest: Request): Request {
        // search for 'getJwt().length() > 0 &&' in userSession
        val isLoggedIn = invokeMethodSafe(userSession, "p") as? Boolean ?: false

        val builder: Builder = originalRequest.newBuilder()

        if (isLoggedIn) {
            // search for a one line method that returns a StateFlow<String> in userSession
            val authTokenFlow = invokeMethodSafe(userSession, "x")
            val authToken = if (authTokenFlow != null) {
                invokeMethodSafe(authTokenFlow, "getValue") as? String ?: ""
            } else {
                ""
            }

            // search for path_segment_encode_set_uri in userSession
            val roles = invokeMethodSafe(userSession, "E") as? String ?: ""

            if (authToken.isNotEmpty()) {
                builder.header("Authorization", "Grindr3 $authToken")
                builder.header("L-Grindr-Roles", roles)
            } else {
                Logger.w("Auth token is empty, skipping auth headers", LogSource.HTTP)
            }

            builder.header("L-Time-Zone", TimeZone.getDefault().id)

            // search for 'public final kotlin.Lazy' in deviceInfo
            val deviceInfoLazy = getFieldSafe(deviceInfo, "d") as? Any
            val lDeviceInfo = if (deviceInfoLazy != null) {
                invokeMethodSafe(deviceInfoLazy, "getValue") as? String ?: ""
            } else {
                ""
            }

            if (lDeviceInfo.isNotEmpty()) {
                builder.header("L-Device-Info", lDeviceInfo)
            }
        } else {
            builder.header("L-Time-Zone", "Unknown")
        }

        // search for 'getValue().getNameTitleCase()' in userAgent
        val userAgentString = invokeMethodSafe(userAgent, "a") as? String ?: "Grindr"

        builder.header("Accept", "application/json; charset=UTF-8")
        builder.header("User-Agent", userAgentString)
        builder.header("L-Locale", "en_US")
        builder.header("Accept-language", "en-US")

        return builder.build()
    }

    /**
     * Reflectively invoke a no-arg method on [obj]. Returns `null` and
     * logs a warning if the method is missing (typical after a Grindr
     * update that renamed the symbol). Never throws.
     */
    private fun invokeMethodSafe(obj: Any?, methodName: String): Any? {
        return try {
            if (obj == null) {
                Logger.w("Object is null when trying to invoke method: $methodName", LogSource.HTTP)
                return null
            }

            val method = obj::class.java.getMethod(methodName)
            val result = method.invoke(obj)
            result
        } catch (e: NoSuchMethodException) {
            Logger.e("Method not found: $methodName on ${obj?.javaClass?.simpleName}", LogSource.HTTP)
            null
        } catch (e: Exception) {
            Logger.e("Failed to invoke method $methodName: ${e.message}", LogSource.HTTP)
            Logger.writeRaw(e.stackTraceToString())
            null
        }
    }

    /**
     * Reflectively read a field on [obj] (including private). Returns
     * `null` and logs a warning if the field is missing. Never throws.
     */
    private fun getFieldSafe(obj: Any?, fieldName: String): Any? {
        return try {
            if (obj == null) {
                Logger.w("Object is null when trying to get field: $fieldName", LogSource.HTTP)
                return null
            }

            val field = obj::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            val value = field.get(obj)
            value
        } catch (e: NoSuchFieldException) {
            Logger.e("Field not found: $fieldName on ${obj?.javaClass?.simpleName}", LogSource.HTTP)
            null
        } catch (e: Exception) {
            Logger.e("Failed to get field $fieldName: ${e.message}", LogSource.HTTP)
            Logger.writeRaw(e.stackTraceToString())
            null
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        return try {
            val modifiedRequest = modifyRequest(request)
            Logger.d("Intercepting request to: ${request.url}", LogSource.HTTP)
            chain.proceed(modifiedRequest)
        } catch (e: SocketTimeoutException) {
            Logger.e("Request timeout: ${e.message}", LogSource.HTTP)
            createErrorResponse(request, 408, "Request Timeout")
        } catch (e: TimeoutException) {
            Logger.e("Request timeout: ${e.message}", LogSource.HTTP)
            createErrorResponse(request, 408, "Request Timeout")
        } catch (e: IOException) {
            Logger.e("Network error: ${e.message}", LogSource.HTTP)
            Logger.writeRaw(e.stackTraceToString())
            createErrorResponse(request, 503, "Network Error")
        } catch (e: Exception) {
            Logger.e("Unexpected error: ${e.message}", LogSource.HTTP)
            Logger.writeRaw(e.stackTraceToString())
            createErrorResponse(request, 500, "Internal Error")
        }
    }

    private fun createErrorResponse(request: Request, code: Int, message: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .body("".toResponseBody(null))
            .build()
    }
}