package io.github.bbzq.hooks

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.bbzq.ModuleSettings
import io.github.libxposed.api.XposedInterface

class MiniGameRewardAdHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    override fun startHook() {
        val rewardClasses = REWARD_ACTIVITY_CLASSES
            .mapNotNull { HostAccess.findClass(classLoader, it) }
            .distinct()
        val onCreateMethods = rewardClasses.mapNotNull { type ->
            HostAccess.method(type, listOf("onCreate")) {
                it.parameterTypes.contentEquals(arrayOf(Bundle::class.java))
            }
        }.distinct()

        onCreateMethods.forEach { method ->
            xposed.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (ModuleSettings.isSkipMiniGameRewardAdEnabled(prefs)) {
                        (chain.thisObject as? Activity)?.let(::completeRewardAd)
                    }
                    result
                }
        }
        log("Installed reward ad completion hook on ${onCreateMethods.size} activity method(s)")
    }

    private fun completeRewardAd(activity: Activity) {
        val booleanFields = HostAccess.fields(activity.javaClass)
            .filter { it.type == Boolean::class.javaPrimitiveType }
            .toList()
        val rewardField = booleanFields.getOrNull(1) ?: booleanFields.firstOrNull {
            it.name.contains("reward", true) ||
                it.name.contains("finish", true) ||
                it.name.contains("skip", true)
        }
        rewardField?.let { runCatching { it.setBoolean(activity, true) } }

        val decor = activity.window?.decorView ?: return
        REWARD_SCAN_DELAYS.forEach { delay ->
            decor.postDelayed({
                findRewardAction(decor)?.performClick()
                    ?: findFirstTextView(activity)?.performClick()
            }, delay)
        }
    }

    private fun findFirstTextView(activity: Activity): TextView? =
        HostAccess.fields(activity.javaClass)
            .firstOrNull { TextView::class.java.isAssignableFrom(it.type) }
            ?.let { runCatching { it.get(activity) as? TextView }.getOrNull() }

    private fun findRewardAction(view: View): View? {
        if (!view.isShown) return null
        val text = when (view) {
            is TextView -> view.text?.toString().orEmpty()
            else -> view.contentDescription?.toString().orEmpty()
        }.replace(" ", "")
        if (
            text.isNotEmpty() &&
            REWARD_ACTION_TEXTS.any(text::contains) &&
            (view.isClickable || view.hasOnClickListeners())
        ) {
            return view
        }

        val group = view as? ViewGroup ?: return null
        for (index in group.childCount - 1 downTo 0) {
            findRewardAction(group.getChildAt(index))?.let { return it }
        }
        return null
    }

    private companion object {
        private val REWARD_ACTIVITY_CLASSES = listOf(
            "com.bilibili.ad.reward.RewardAdActivity",
            "com.bilibili.ad.reward.activity.BaseRewardAdActivity",
        )
        private val REWARD_ACTION_TEXTS = listOf("跳过", "领取奖励", "立即领取", "关闭广告")
        private val REWARD_SCAN_DELAYS = longArrayOf(0L, 150L, 500L, 1000L)
    }
}
