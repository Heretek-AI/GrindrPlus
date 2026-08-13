package com.grindrplus.hooks

import android.annotation.SuppressLint
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.setObjectField
import de.robv.android.xposed.XposedHelpers.findAndHookConstructor
import de.robv.android.xposed.XposedHelpers.findAndHookMethod
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * SSL certificate pinning bypass.
 *
 * Unlike the rest of the hooks in this package (which extend
 * [com.grindrplus.utils.Hook] and register via [com.grindrplus.utils.HookManager]),
 * this file is a top-level [sslUnpinning] function called directly from
 * [com.grindrplus.XposedLoader.handleLoadPackage] under `BuildConfig.DEBUG`.
 * It runs before any Grindr network call so the host app's HTTPS stack
 * never rejects our Mitmproxy / HTTP Toolkit cert.
 *
 * The hooks here target stable, fully-qualified classes from OkHttp /
 * Conscrypt / Firebase; the obfuscation-marker convention doesn't apply.
 *
 * @see com.grindrplus.XposedLoader for the call site.
 */
@OptIn(ExperimentalStdlibApi::class)
@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager", "BadHostnameVerifier")
fun sslUnpinning(param: XC_LoadPackage.LoadPackageParam) {
    findAndHookConstructor(
        "okhttp3.OkHttpClient\$Builder",
        param.classLoader,
        object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                callMethod(
                    param.thisObject,
                    "sslSocketFactory",
                    unsafeSslContext.socketFactory,
                    unsafeTrustManager
                )
                // the builder does not have hostnameVerifier() method
                setObjectField(
                    param.thisObject,
                    "hostnameVerifier",
                    object : HostnameVerifier {
                        override fun verify(hostname: String?, session: SSLSession?): Boolean = true
                    }
                )
            }
        }
    )

    findAndHookMethod(
        "okhttp3.OkHttpClient\$Builder",
        param.classLoader,
        "certificatePinner",
        "okhttp3.CertificatePinner",
        XC_MethodReplacement.DO_NOTHING
    )

    findAndHookMethod(
        "com.android.org.conscrypt.TrustManagerImpl",
        param.classLoader,
        "verifyChain",
        List::class.java, // List<X509Certificate> untrustedChain
        List::class.java, // List<TrustAnchor> trustAnchorChain
        String::class.java, // String host
        Boolean::class.java, // boolean clientAuth
        ByteArray::class.java, // byte[] ocspData
        ByteArray::class.java, // byte[] tlsSctData
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                param.result = param.args[0]
            }
        }
    )
}

val unsafeTrustManager = @SuppressLint("CustomX509TrustManager")
object : X509TrustManager {
    @SuppressLint("TrustAllX509TrustManager")
    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) {
    }

    @SuppressLint("TrustAllX509TrustManager")
    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) {
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

val unsafeSslContext: SSLContext = SSLContext.getInstance("TLSv1.3").apply {
    val trustAlLCerts = arrayOf<TrustManager>(unsafeTrustManager)
    this.init(null, trustAlLCerts, SecureRandom())
}