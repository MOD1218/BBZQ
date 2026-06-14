package io.github.bbzq.hooks

import io.github.bbzq.ModuleSettings
import io.github.libxposed.api.XposedInterface

class UnlockCommentGifHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    override fun startHook() {
        var count = 0
        REPLY_CONTAINER_CLASSES.forEach { (className, methods) ->
            val type = HostAccess.findClass(classLoader, className) ?: return@forEach
            HostAccess.methods(type)
                .filter { it.parameterCount == 0 && it.name in methods }
                .forEach { method ->
                    xposed.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept { chain ->
                            val result = chain.proceed()
                            if (ModuleSettings.isUnlockCommentGifEnabled(prefs)) {
                                when (result) {
                                    is Iterable<*> -> result.forEach { unlockReply(it, 1.5f) }
                                    is Array<*> -> result.forEach { unlockReply(it, 1.5f) }
                                    else -> unlockReply(result, if (method.name == "getRoot") 1.0f else 1.5f)
                                }
                            }
                            result
                        }
                    count++
                }
        }
        log("Installed $count comment GIF hook(s)")
    }

    private fun unlockReply(reply: Any?, scale: Float) {
        if (reply == null) return
        val content = HostAccess.get(reply, "content")
        if (content != null) {
            HostAccess.set(content, scale, "pictureScale", "picture_scale")
            HostAccess.asIterable(HostAccess.get(content, "picturesList", "pictures"))
                .forEach { picture ->
                    if (picture == null) return@forEach
                    HostAccess.set(picture, true, "playGifThumbnail", "play_gif_thumbnail")
                    HostAccess.clear(picture, "topRightIcon", "top_right_icon")
                }
        }

        HostAccess.asIterable(HostAccess.get(reply, "repliesList", "replies"))
            .forEach { child -> unlockReply(child, scale) }
    }

    private companion object {
        private val REPLY_CONTAINER_CLASSES = mapOf(
            "com.bapis.bilibili.main.community.reply.v1.MainListReply" to setOf(
                "getUpTop",
                "getAdminTop",
                "getVoteTop",
                "getTopRepliesList",
                "getRepliesList",
            ),
            "com.bapis.bilibili.main.community.reply.v1.DetailListReply" to setOf(
                "getRoot",
                "getRepliesList",
            ),
        )
    }
}
