package io.github.bbzq.roaming.hook

import android.content.SharedPreferences
import io.github.bbzq.ModuleSettings
import io.github.bbzq.roaming.BaseRoamingHook
import io.github.bbzq.roaming.RoamingEnv
import io.github.bbzq.roaming.from
import io.github.bbzq.roaming.getObjectField
import io.github.bbzq.roaming.hookBefore
import io.github.bbzq.roaming.methodsNamed
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class StoryPlayerAdHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (!ModuleSettings.isPurifyStoryVideoAdEnabled(prefs)) return

        val storyPagerPlayer = "com.bilibili.video.story.player.StoryPagerPlayer".from(classLoader)
            ?: return
        val addVideo = storyPagerPlayer.methodsNamed(null)
            .firstOrNull(::isStoryListMethod)
            ?: return

        env.hookBefore(addVideo) { param ->
            val storyDetailList = param.args.firstOrNull() as? MutableList<Any?> ?: return@hookBefore
            filterStoryList(storyDetailList)
        }
        log("startHook: StoryPlayerAd at ${addVideo.declaringClass.name}.${addVideo.name}")
    }

    private fun isStoryListMethod(method: Method): Boolean =
        method.returnType == Void.TYPE &&
            !Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 1 &&
            List::class.java.isAssignableFrom(method.parameterTypes[0])

    private fun filterStoryList(items: MutableList<Any?>) {
        val selectedTags = ModuleSettings.getPurifyStoryVideoAdTags(prefs)
        if (selectedTags.isEmpty()) return

        val tags = ModuleSettings.storyVideoAdTags.associateBy { it.key }
        val before = items.size
        items.removeAll { item ->
            if (item == null) return@removeAll false
            if ("ad" in selectedTags && isStoryAd(item)) return@removeAll true
            val entryText = readCartEntryText(item) ?: return@removeAll false
            selectedTags.any { key -> tags[key]?.cartText == entryText }
        }

        val removed = before - items.size
        if (removed > 0) incrementBlockedCount(prefs, removed)
    }

    private fun isStoryAd(item: Any): Boolean =
        runCatching {
            item.javaClass.getDeclaredMethod("isAd").apply { isAccessible = true }.invoke(item) as? Boolean
        }.getOrNull()
            ?: (item.getObjectField("ad") as? Boolean)
            ?: false

    private fun readCartEntryText(item: Any): String? {
        val cart = runCatching {
            item.javaClass.getDeclaredMethod("getCartIconInfo").apply { isAccessible = true }.invoke(item)
        }.getOrNull() ?: item.getObjectField("cartIconInfo")
        return cart?.let {
            runCatching {
                it.javaClass.getDeclaredMethod("getEntryText").apply { isAccessible = true }.invoke(it) as? String
            }.getOrNull() ?: it.getObjectField("entryText")?.toString()
        }
    }

    private fun incrementBlockedCount(prefs: SharedPreferences, count: Int) {
        val oldValue = prefs.getInt(ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_BLOCKED_COUNT, 0)
        prefs.edit().putInt(ModuleSettings.KEY_PURIFY_STORY_VIDEO_AD_BLOCKED_COUNT, oldValue + count).apply()
    }
}
