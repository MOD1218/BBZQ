package io.github.bzzq.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import kotlin.io.use
import kotlin.concurrent.thread

object HookRegistry {
    private val targetPackageNames = setOf(
        "tv.danmaku.bili",
        "com.bilibili.app.in",
        "tv.danmaku.bilibilihd",
        "com.bilibili.app.blue",
    )

    private val eagerHookFactories: List<(String) -> BaseHook> = listOf(
        ::PackageLoadLogHook,
        ::HostResponseHook,
    )

    private val deferredHookFactories: List<(String) -> BaseHook> = listOf(
        ::VideoFeatureUnlockHook,
        ::AutoLikeVideoDetailHook,
        ::BlockLiveReservationHook,
        ::LiveQualityHook,
        ::StoryVideoAdHook,
        ::MiniGameRewardAdHook,
        ::BiliEntryHook,
        ::MinePageEntryHook,
        ::AccessKeyCaptureHook,
        ::FreeCopyHook,
        ::SharePurifyHook,
        ::FullNumberFormatHook,
        ::UnlockCommentGifHook,
        ::SkipVideoAdHook,
    )

    fun handlePackageReady(
        xposed: XposedInterface,
        packageReady: PackageReadyParam,
        log: (String, Throwable?) -> Unit,
    ) {
        val packageName = packageReady.getPackageName()
        if (packageName !in targetPackageNames) return

        val eagerHooks = eagerHookFactories.map { factory -> factory(packageName) }
        val deferredHooks = deferredHookFactories.map { factory -> factory(packageName) }

        log(
            "Installing ${eagerHooks.size} eager hook(s) and ${deferredHooks.size} deferred hook(s) for $packageName",
            null,
        )
        HookContext(xposed, packageReady, log).use { context ->
            installHooks(packageName, eagerHooks, context, log)
        }

        if (deferredHooks.isEmpty()) return

        thread(
            start = true,
            isDaemon = true,
            name = "bzzq-$packageName-deferred-hooks",
        ) {
            HookContext(xposed, packageReady, log).use { context ->
                installHooks(packageName, deferredHooks, context, log)
            }
        }
    }

    private fun installHooks(
        packageName: String,
        hooks: List<BaseHook>,
        context: HookContext,
        log: (String, Throwable?) -> Unit,
    ) {
        hooks.forEach { hook ->
            runCatching { hook.install(context) }
                .onFailure { log("Hook failed for $packageName", it) }
        }
        hooks.forEach { hook ->
            runCatching { hook.lateInitHook() }
                .onFailure { log("Late hook failed for $packageName", it) }
        }
    }
}
