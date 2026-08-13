package com.grindrplus.core

/**
 * Project-wide constants. Anything here is referenced from multiple modules
 * (hooks, manager, persistence) so it lives in one place.
 */
object Constants {
    /**
     * Sentinel string substituted for newlines when bridging between
     * the Xposed hook context (where `"\n"` can be stripped or escaped
     * by the host app) and the manager UI / chat-console.
     */
    const val NEWLINE = "GRINDRPLUS_NEWLINE"

    /**
     * The Grindr Android application package name. Used by [com.grindrplus.XposedLoader]
     * to gate `handleLoadPackage`, by `core/http/Interceptor` to scope
     * request rewriting, and by the manager UI's package picker.
     */
    const val GRINDR_PACKAGE_NAME = "com.grindrapp.android"
}