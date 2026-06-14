package io.github.bbzq.hooks

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import io.github.bbzq.ModuleSettings
import io.github.libxposed.api.XposedInterface
import java.net.HttpURLConnection
import java.net.URL

class SharePurifyHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    override fun startHook() {
        var count = hookShareResult()
        count += hookClipboardFallback()
        log("Installed $count share purification hook(s)")
    }

    private fun hookShareResult(): Int {
        val shareResult = HostAccess.findClass(
            classLoader,
            "com.bilibili.lib.sharewrapper.online.api.ShareClickResult",
        ) ?: return 0
        var count = 0
        HostAccess.methods(shareResult)
            .filter {
                it.parameterCount == 0 &&
                    it.returnType == String::class.java &&
                    it.name in setOf("getLink", "getContent")
            }
            .forEach { method ->
                xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val rawResult = chain.proceed()
                        val result = rawResult as? String ?: return@intercept rawResult
                        if (!ModuleSettings.isPurifyShareEnabled(prefs)) return@intercept result
                        val purified = if (method.name == "getLink") {
                            purifyLink(result)
                        } else {
                            purifyText(result)
                        }
                        chain.thisObject?.let { target ->
                            val field = if (method.name == "getLink") "link" else "content"
                            HostAccess.set(target, purified, field)
                        }
                        purified
                    }
                count++
            }
        return count
    }

    private fun hookClipboardFallback(): Int {
        val method = ClipboardManager::class.java.getDeclaredMethod("setPrimaryClip", ClipData::class.java)
        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                if (!ModuleSettings.isPurifyShareEnabled(prefs)) return@intercept chain.proceed()
                val clip = chain.getArg(0) as? ClipData ?: return@intercept chain.proceed()
                val text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                    ?: return@intercept chain.proceed()
                val purified = purifyText(text)
                if (purified == text) {
                    chain.proceed()
                } else {
                    chain.proceed(arrayOf<Any>(ClipData.newPlainText(clip.description.label, purified)))
                }
            }
        return 1
    }

    private fun purifyText(text: String): String = URL_REGEX.replace(text) { match ->
        val raw = match.value
        val suffix = raw.takeLastWhile { it in TRAILING_PUNCTUATION }
        val url = raw.dropLast(suffix.length)
        transformUrl(resolveShortLink(url)) + suffix
    }

    private fun purifyLink(url: String): String =
        transformUrl(resolveShortLink(url))

    private fun transformUrl(url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val host = uri.host.orEmpty()
        if (!host.endsWith("bilibili.com") && host !in SHORT_LINK_HOSTS) return url

        val builder = uri.buildUpon().clearQuery()
        uri.encodedQuery
            ?.split("&")
            ?.mapNotNull { segment ->
                val parts = segment.split("=", limit = 2)
                if (parts.size != 2) null else parts[0] to parts[1]
            }
            ?.forEach { (name, value) ->
                when (name) {
                    "p", "t" -> builder.appendQueryParameter(name, value)
                    "start_progress" -> {
                        builder.appendQueryParameter("start_progress", value)
                        value.toLongOrNull()?.let { millis ->
                            builder.appendQueryParameter("t", (millis / 1000).toString())
                        }
                    }
                }
            }
        if (uri.getQueryParameter("unique_k").isNullOrEmpty()) {
            builder.appendQueryParameter("unique_k", "2333")
        }
        return builder.fragment(null).build().toString()
    }

    private fun resolveShortLink(url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        if (uri.host !in SHORT_LINK_HOSTS) return url
        val requestUrl = uri.buildUpon().query(null).fragment(null).build().toString()
        return runCatching {
            (URL(requestUrl).openConnection() as HttpURLConnection).run {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 5000
                readTimeout = 5000
                connect()
                when (responseCode) {
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_MOVED_PERM,
                    307,
                    308,
                    -> getHeaderField("Location") ?: url

                    else -> url
                }
            }
        }.getOrDefault(url)
    }

    private companion object {
        private val URL_REGEX = Regex("""https?://\S+""")
        private val SHORT_LINK_HOSTS = setOf("b23.tv", "bili2233.cn")
        private val TRAILING_PUNCTUATION = setOf(')', ']', '>', ',', '.', ';', '!', '?')
    }
}
