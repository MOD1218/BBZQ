package io.github.bbzq.hooks

import io.github.bbzq.ModuleSettings
import io.github.libxposed.api.XposedInterface

class BlockLiveReservationHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    override fun startHook() {
        var hookCount = 0
        VIEW_REPLY_CLASSES.mapNotNull { HostAccess.findClass(classLoader, it) }
            .distinct()
            .forEach { type ->
                HostAccess.methods(type)
                    .filter { it.parameterCount == 0 && it.name == "hasLiveOrderInfo" }
                    .forEach { method ->
                        xposed.hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept { chain ->
                                if (ModuleSettings.isBlockLiveReservationEnabled(prefs)) false else chain.proceed()
                            }
                        hookCount++
                    }
            }

        VIEW_REPLY_BUILDER_CLASSES.mapNotNull { HostAccess.findClass(classLoader, it) }
            .distinct()
            .forEach { type ->
                HostAccess.methods(type)
                    .filter { it.name == "build" && it.parameterCount == 0 }
                    .forEach { method ->
                        xposed.hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept { chain ->
                                if (ModuleSettings.isBlockLiveReservationEnabled(prefs)) {
                                    chain.thisObject?.let { builder ->
                                        HostAccess.invoke(builder, "clearLiveOrderInfo")
                                    }
                                }
                                chain.proceed()
                            }
                        hookCount++
                    }
            }

        log("Installed $hookCount live reservation hook(s)")
    }

    private companion object {
        private val VIEW_REPLY_CLASSES = listOf(
            "com.bapis.bilibili.app.view.v1.ViewReply",
            "com.bapis.bilibili.app.viewunite.v1.ViewReply",
        )
        private val VIEW_REPLY_BUILDER_CLASSES = VIEW_REPLY_CLASSES.map { "$it\$Builder" }
    }
}
