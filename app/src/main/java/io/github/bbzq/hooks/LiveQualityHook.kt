package io.github.bbzq.hooks

import android.net.Uri
import io.github.bbzq.ModuleSettings
import io.github.libxposed.api.XposedInterface
import org.json.JSONArray
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class LiveQualityHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    @Volatile
    private var selectedQn = DEFAULT_QN.toString()

    override fun startHook() {
        val resolver = HostMethodResolver(context)
        val selector = resolver.resolve(
            cacheKey = "live_quality_selector",
            fixedCandidates = {
                LIVE_SELECTOR_CLASSES.asSequence()
                    .mapNotNull { HostAccess.findClass(classLoader, it) }
                    .flatMap(HostAccess::methods)
            },
            searchPackages = listOf("com.bilibili.bililive"),
            usingStrings = listOf("LiveUrlSelectorData(playUrl="),
            validate = ::isSelectorMethod,
        )
        selector?.let(::hookSelector)

        val interceptor = resolver.resolve(
            cacheKey = "live_quality_interceptor",
            fixedCandidates = { emptySequence() },
            searchPackages = listOf("com.bilibili", "tv.danmaku.bili"),
            usingStrings = listOf("inject common param to body failure"),
            validate = ::isRequestInterceptor,
        )
        if (interceptor != null) {
            hookRequestInterceptor(interceptor)
        } else {
            hookHttpUrlFallback()
        }

        log(
            "Installed live quality hook: selector=${selector?.declaringClass?.name ?: "missing"}, " +
                "interceptor=${interceptor?.declaringClass?.name ?: "HttpUrl fallback"}",
        )
    }

    private fun hookSelector(method: Method) {
        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                if (!ModuleSettings.isFixLiveQualityUrlEnabled(prefs)) return@intercept chain.proceed()
                val uri = chain.getArg(0) as? Uri ?: return@intercept chain.proceed()
                val rewritten = rewriteSelectorUri(uri) ?: return@intercept chain.proceed()
                chain.proceed(arrayOf<Any>(rewritten))
            }
    }

    private fun hookRequestInterceptor(method: Method) {
        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                if (ModuleSettings.isFixLiveQualityUrlEnabled(prefs)) {
                    chain.getArg(0)?.let(::rewriteRequestUrl)
                }
                chain.proceed()
            }
    }

    private fun hookHttpUrlFallback() {
        HTTP_URL_CLASSES.mapNotNull { HostAccess.findClass(classLoader, it) }
            .distinct()
            .forEach { httpUrlClass ->
                HostAccess.methods(httpUrlClass)
                    .filter { method ->
                        Modifier.isStatic(method.modifiers) &&
                            method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                            httpUrlClass.isAssignableFrom(method.returnType)
                    }
                    .forEach { method ->
                        xposed.hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept { chain ->
                                if (!ModuleSettings.isFixLiveQualityUrlEnabled(prefs)) {
                                    return@intercept chain.proceed()
                                }
                                val original = chain.getArg(0) as? String ?: return@intercept chain.proceed()
                                val rewritten = rewriteRoomPlayInfoUrl(original) ?: return@intercept chain.proceed()
                                chain.proceed(arrayOf<Any>(rewritten))
                            }
                    }
            }
    }

    private fun rewriteRequestUrl(request: Any) {
        val urlField = HostAccess.fields(request.javaClass)
            .firstOrNull { it.type.name.endsWith("HttpUrl") }
            ?: return
        val oldUrl = runCatching { urlField.get(request)?.toString() }.getOrNull() ?: return
        val newUrl = rewriteRoomPlayInfoUrl(oldUrl) ?: return
        val parseMethod = HostAccess.methods(urlField.type).firstOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                urlField.type.isAssignableFrom(method.returnType)
        } ?: return
        val parsed = runCatching { parseMethod.invoke(null, newUrl) }.getOrNull() ?: return
        runCatching { urlField.set(request, parsed) }
    }

    private fun rewriteSelectorUri(uri: Uri): Uri? {
        if (!uri.isLiveRoom()) return null
        if (uri.getQueryParameter("no_playurl") == "1") {
            selectedQn = DEFAULT_QN.toString()
            return null
        }

        val accepted = uri.getQueryParameter("accept_quality")
        selectedQn = accepted?.let(::selectQuality)?.toString() ?: DEFAULT_QN.toString()
        val builder = uri.buildUpon().clearQuery()
        uri.queryParameterNames.forEach { name ->
            if (!name.startsWith("playurl")) {
                builder.appendQueryParameter(name, uri.getQueryParameter(name).orEmpty())
            }
        }
        builder.appendQueryParameter("no_playurl", "1")
        return builder.build()
    }

    private fun rewriteRoomPlayInfoUrl(url: String): String? {
        if (!url.startsWith(ROOM_PLAY_INFO_PREFIX)) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val currentQn = uri.getQueryParameter("qn")
        if (!currentQn.isNullOrEmpty() && currentQn != "0") return null

        val builder = uri.buildUpon().clearQuery()
        var foundQn = false
        uri.queryParameterNames.forEach { name ->
            val value = if (name == "qn") {
                foundQn = true
                selectedQn
            } else {
                uri.getQueryParameter(name).orEmpty()
            }
            builder.appendQueryParameter(name, value)
        }
        if (!foundQn) builder.appendQueryParameter("qn", selectedQn)
        return builder.build().toString()
    }

    private fun selectQuality(json: String): Int {
        val accepted = runCatching {
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    array.optInt(index).takeIf { it > 0 }?.let(::add)
                }
            }.sorted()
        }.getOrDefault(emptyList())
        if (accepted.isEmpty()) return DEFAULT_QN
        return accepted.firstOrNull { it >= DEFAULT_QN } ?: accepted.last()
    }

    private fun Uri.isLiveRoom(): Boolean =
        scheme in setOf("http", "https") &&
            host == "live.bilibili.com" &&
            pathSegments.firstOrNull()?.all(Char::isDigit) == true

    private fun isSelectorMethod(method: Method): Boolean =
        method.parameterTypes.contentEquals(arrayOf(Uri::class.java)) &&
            method.returnType != Void.TYPE &&
            method.declaringClass.name.contains("bililive")

    private fun isRequestInterceptor(method: Method): Boolean =
        method.parameterCount == 1 &&
            method.returnType == method.parameterTypes[0] &&
            method.returnType != Void.TYPE

    private companion object {
        private const val DEFAULT_QN = 10000
        private const val ROOM_PLAY_INFO_PREFIX =
            "https://api.live.bilibili.com/xlive/app-room/v2/index/getRoomPlayInfo?"
        private val LIVE_SELECTOR_CLASSES = listOf(
            "com.bilibili.bililive.room.ui.roomv3.player.LivePlayUrlSelectUtil",
            "com.bilibili.bililive.room.ui.roomv3.player.playurl.LivePlayUrlSelectUtil",
            "com.bilibili.bililive.room.ui.roomv3.player.selector.LivePlayUrlSelectUtil",
        )
        private val HTTP_URL_CLASSES = listOf(
            "okhttp3.HttpUrl",
            "com.bilibili.lib.okhttp.HttpUrl",
        )
    }
}
