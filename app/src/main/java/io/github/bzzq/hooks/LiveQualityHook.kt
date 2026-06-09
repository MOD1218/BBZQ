package io.github.bzzq.hooks

import android.content.SharedPreferences
import android.net.Uri
import io.github.bzzq.ModuleSettings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.json.JSONArray
import java.lang.reflect.Modifier

class LiveQualityHook(
    override val targetPackageName: String,
) : AppHook {
    @Volatile
    private var nextQn: String = ""

    override fun install(xposed: XposedInterface, packageReady: PackageReadyParam, log: (String, Throwable?) -> Unit) {
        val classLoader = packageReady.getClassLoader()
        val prefs = xposed.getRemotePreferences(ModuleSettings.PREFS_NAME)

        hookSelectorUri(xposed, classLoader, prefs, log)
        hookHttpUrlParse(xposed, classLoader, prefs, log)
    }

    private fun hookSelectorUri(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        prefs: SharedPreferences,
        log: (String, Throwable?) -> Unit,
    ) {
        val selectorClasses = LIVE_PLAY_URL_SELECT_UTIL_CLASS_NAMES.mapNotNull { className ->
            runCatching { Class.forName(className, false, classLoader) }.getOrNull()
        }
        if (selectorClasses.isEmpty()) {
            log("Live play URL selector class not found", null)
            return
        }

        var hookCount = 0
        selectorClasses.forEach { selectorClass ->
            selectorClass.declaredMethods
                .filter { method -> method.parameterTypes.contentEquals(arrayOf(Uri::class.java)) }
                .forEach { method ->
                    method.isAccessible = true
                    xposed.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept { chain ->
                            if (!ModuleSettings.isFixLiveQualityUrlEnabled(prefs)) {
                                return@intercept chain.proceed()
                            }

                            val originalUri = chain.getArg(0) as? Uri
                                ?: return@intercept chain.proceed()
                            val newUri = rewriteLiveSelectorUri(originalUri)
                                ?: return@intercept chain.proceed()
                            chain.proceed(arrayOf<Any>(newUri))
                        }
                    hookCount++
                }
        }

        if (hookCount > 0) {
            log("Installed live selector URL hook(s): $hookCount", null)
        } else {
            log("Live selector URL method not found", null)
        }
    }

    private fun hookHttpUrlParse(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        prefs: SharedPreferences,
        log: (String, Throwable?) -> Unit,
    ) {
        val httpUrlClasses = HTTP_URL_CLASS_NAMES.mapNotNull { className ->
            runCatching { Class.forName(className, false, classLoader) }.getOrNull()
        }
        if (httpUrlClasses.isEmpty()) {
            log("HttpUrl class not found for live quality hook", null)
            return
        }

        var hookCount = 0
        httpUrlClasses.forEach { httpUrlClass ->
            httpUrlClass.declaredMethods
                .filter { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                        httpUrlClass.isAssignableFrom(method.returnType)
                }
                .forEach { method ->
                    method.isAccessible = true
                    xposed.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept { chain ->
                            if (!ModuleSettings.isFixLiveQualityUrlEnabled(prefs)) {
                                return@intercept chain.proceed()
                            }

                            val url = chain.getArg(0) as? String
                                ?: return@intercept chain.proceed()
                            val newUrl = rewriteRoomPlayInfoUrl(url)
                                ?: return@intercept chain.proceed()
                            chain.proceed(arrayOf<Any>(newUrl))
                        }
                    hookCount++
                }
        }

        if (hookCount > 0) {
            log("Installed live room play info URL hook(s): $hookCount", null)
        } else {
            log("HttpUrl parse method not found for live quality hook", null)
        }
    }

    private fun rewriteLiveSelectorUri(originalUri: Uri): Uri? {
        if (!originalUri.isLiveRoomUrl()) return null

        if (originalUri.getQueryParameter(NO_PLAYURL_QUERY) == "1") {
            nextQn = PREFERRED_LIVE_QN.toString()
            return null
        }

        val acceptQuality = originalUri.getQueryParameter(ACCEPT_QUALITY_QUERY)
            ?: return null
        nextQn = runCatching {
            findQuality(JSONArray(acceptQuality), PREFERRED_LIVE_QN).toString()
        }.getOrDefault(PREFERRED_LIVE_QN.toString())

        return originalUri.modified(
            removeIf = { name -> name.startsWith(PLAYURL_QUERY_PREFIX) },
            append = mapOf(NO_PLAYURL_QUERY to "1"),
        )
    }

    private fun rewriteRoomPlayInfoUrl(url: String): String? {
        if (!url.startsWith(ROOM_PLAY_INFO_URL_PREFIX)) return null

        val originalUri = Uri.parse(url)
        val qn = originalUri.getQueryParameter(QN_QUERY)
        if (!qn.isNullOrEmpty() && qn != "0") return null

        var hasQn = false
        val builder = originalUri.buildUpon().clearQuery()
        originalUri.queryParameterNames.forEach { name ->
            val value = if (name == QN_QUERY) {
                hasQn = true
                nextQn.ifEmpty { PREFERRED_LIVE_QN.toString() }
            } else {
                originalUri.getQueryParameter(name) ?: ""
            }
            builder.appendQueryParameter(name, value)
        }
        if (!hasQn) {
            builder.appendQueryParameter(QN_QUERY, nextQn.ifEmpty { PREFERRED_LIVE_QN.toString() })
        }
        return builder.build().toString()
    }

    private fun Uri.isLiveRoomUrl(): Boolean {
        return scheme in arrayOf("http", "https") &&
            host == "live.bilibili.com" &&
            pathSegments.firstOrNull()?.all { it.isDigit() } == true
    }

    private fun Uri.modified(removeIf: (String) -> Boolean, append: Map<String, Any>): Uri {
        val builder = buildUpon().clearQuery()
        queryParameterNames.forEach { name ->
            if (!removeIf(name)) {
                builder.appendQueryParameter(name, getQueryParameter(name) ?: "")
            }
        }
        append.forEach { (name, value) ->
            builder.appendQueryParameter(name, value.toString())
        }
        return builder.build()
    }

    private fun findQuality(acceptQuality: JSONArray, expectQuality: Int): Int {
        val acceptQnList = buildList {
            for (index in 0 until acceptQuality.length()) {
                add(acceptQuality.optInt(index))
            }
        }.filter { it > 0 }.sorted()
        if (acceptQnList.isEmpty()) return expectQuality

        val max = acceptQnList.last()
        val min = acceptQnList.first()
        return when {
            expectQuality > max -> max
            expectQuality < min -> min
            else -> acceptQnList.first { it >= expectQuality }
        }
    }

    private companion object {
        private const val ROOM_PLAY_INFO_URL_PREFIX =
            "https://api.live.bilibili.com/xlive/app-room/v2/index/getRoomPlayInfo?"
        private const val ACCEPT_QUALITY_QUERY = "accept_quality"
        private const val NO_PLAYURL_QUERY = "no_playurl"
        private const val PLAYURL_QUERY_PREFIX = "playurl"
        private const val QN_QUERY = "qn"
        private const val PREFERRED_LIVE_QN = 10000

        private val LIVE_PLAY_URL_SELECT_UTIL_CLASS_NAMES = listOf(
            "com.bilibili.bililive.room.ui.roomv3.player.LivePlayUrlSelectUtil",
            "com.bilibili.bililive.room.ui.roomv3.player.playurl.LivePlayUrlSelectUtil",
            "com.bilibili.bililive.room.ui.roomv3.player.selector.LivePlayUrlSelectUtil",
            "com.bilibili.bililive.room.ui.roomv3.player.url.LivePlayUrlSelectUtil",
        )

        private val HTTP_URL_CLASS_NAMES = listOf(
            "okhttp3.HttpUrl",
            "com.bilibili.lib.okhttp.HttpUrl",
        )
    }
}
