package io.github.bbzq.roaming.hook

import android.net.Uri
import io.github.bbzq.ModuleSettings
import io.github.bbzq.roaming.BaseRoamingHook
import io.github.bbzq.roaming.RoamingEnv
import io.github.bbzq.roaming.from
import io.github.bbzq.roaming.getObjectField
import io.github.bbzq.roaming.hookAfterMethod
import io.github.bbzq.roaming.setObjectField
import java.net.HttpURLConnection
import java.net.URL

class ShareHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private val contentUrlPattern = Regex("""[\s\S]*(https?://(?:bili2233\.cn|b23\.tv)/\S*)$""")

    override fun startHook() {
        val miniProgramEnabled = prefs.getBoolean(ModuleSettings.KEY_MINI_PROGRAM_ENABLED, false)
        val purifyShareEnabled = prefs.getBoolean(ModuleSettings.KEY_PURIFY_SHARE_ENABLED, false)
        if (!miniProgramEnabled && !purifyShareEnabled) return

        val shareClickResult = "com.bilibili.lib.sharewrapper.online.api.ShareClickResult".from(classLoader)
            ?: return
        var count = 0

        if (purifyShareEnabled) {
            count += env.hookAfterMethod(shareClickResult, "getLink") { param ->
                val link = param.result as? String ?: return@hookAfterMethod
                if (!link.isShortLink()) return@hookAfterMethod
                val resolved = Uri.parse(link).buildUpon().query("").build().toString().resolveShortLink()
                param.thisObject?.setObjectField("link", resolved)
                param.result = resolved
            }
            count += env.hookAfterMethod(shareClickResult, "getContent") { param ->
                val content = param.result as? String ?: return@hookAfterMethod
                val contentUrl = contentUrlPattern.matchEntire(content)?.groups?.get(1)?.value
                    ?: return@hookAfterMethod
                val resolved = ((param.thisObject?.getObjectField("link") as? String) ?: contentUrl)
                    .let { if (it.isShortLink()) it.resolveShortLink() else it }
                val transformed = content.replace(contentUrl, transformUrl(resolved, miniProgramEnabled))
                param.thisObject?.setObjectField("content", transformed)
                param.result = transformed
            }
        }

        if (miniProgramEnabled) {
            count += env.hookAfterMethod(shareClickResult, "getShareMode") { param ->
                if (param.result != 6 && param.result != 7) return@hookAfterMethod
                param.result = 0
                val target = param.thisObject ?: return@hookAfterMethod
                if (target.getObjectField("title") == "哔哩哔哩") {
                    target.setObjectField("title", target.getObjectField("content"))
                    target.setObjectField("content", "由 BBZQ 分享")
                }
                (target.getObjectField("content") as? String)
                    ?.takeIf { it.startsWith("已观看") }
                    ?.let { target.setObjectField("content", "$it\n由 BBZQ 分享") }
            }
        }

        log("startHook: Share, methods=$count")
    }

    private fun String.isShortLink(): Boolean =
        startsWith("https://bili2233.cn") ||
            startsWith("http://bili2233.cn") ||
            startsWith("https://b23.tv") ||
            startsWith("http://b23.tv")

    private fun String.resolveShortLink(): String {
        return runCatching {
            val conn = URL(this).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.connect()
            if (conn.responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                conn.responseCode == HttpURLConnection.HTTP_MOVED_PERM
            ) {
                conn.getHeaderField("Location") ?: this
            } else {
                this
            }
        }.getOrDefault(this)
    }

    private fun transformUrl(url: String, transformAv: Boolean): String {
        val target = Uri.parse(url)
        val bv = if (transformAv) {
            target.path?.split("/")?.firstOrNull { it.startsWith("BV") && it.length == 12 }
        } else {
            null
        }
        val av = bv?.let { "av${bv2av(it)}" }
        val newUrl = target.buildUpon()
        if (av != null) {
            newUrl.path(target.path!!.replace(bv, av))
        }
        val encodedQuery = target.encodedQuery
        if (encodedQuery != null) {
            val query = encodedQuery.split("&")
                .map { it.split("=", limit = 2) }
                .filter { it.size == 2 }
                .mapNotNull {
                    when (it[0]) {
                        "p", "t" -> "${it[0]}=${it[1]}"
                        "start_progress" -> "start_progress=${it[1]}&t=${it[1].toLongOrNull()?.div(1000) ?: 0}"
                        else -> null
                    }
                }
                .joinToString("&", postfix = "&unique_k=2333")
            newUrl.encodedQuery(query)
        } else {
            newUrl.appendQueryParameter("unique_k", "2333")
        }
        return newUrl.build().toString()
    }

    private fun bv2av(bv: String): Long {
        val table = HashMap<Char, Int>()
        "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf".forEachIndexed { index, char ->
            table[char] = index
        }
        val positions = intArrayOf(11, 10, 3, 8, 4, 6, 5, 7, 9)
        var result = 0L
        positions.forEachIndexed { index, position ->
            result += (table[bv[position]] ?: 0) * pow58(index)
        }
        return result.and(2251799813685247L).xor(23442827791579L)
    }

    private fun pow58(index: Int): Long {
        var result = 1L
        repeat(index) { result *= 58L }
        return result
    }
}
