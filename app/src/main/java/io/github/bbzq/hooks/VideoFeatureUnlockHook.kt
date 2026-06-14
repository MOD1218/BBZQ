package io.github.bbzq.hooks

import io.github.bbzq.ModuleSettings
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

class VideoFeatureUnlockHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    override fun startHook() {
        var hookCount = 0

        hookCount += hookBooleanAccessors(
            classNames = TRIAL_CLASSES,
            falseMethods = setOf("getIsNeedTrial", "isNeedTrial", "getNeedTrial"),
            trueMethods = emptySet(),
        )
        hookCount += hookBooleanSetters(
            classNames = TRIAL_CLASSES,
            setterNames = setOf("setIsNeedTrial", "setNeedTrial"),
            forcedValue = false,
        )
        hookCount += hookBooleanAccessors(
            classNames = STREAM_INFO_CLASSES,
            falseMethods = setOf("getNeedVip", "isNeedVip"),
            trueMethods = setOf("getVipFree", "isVipFree"),
        )
        hookCount += hookBooleanAccessors(
            classNames = ARC_CONF_CLASSES,
            falseMethods = setOf("getDisabled", "isDisabled"),
            trueMethods = setOf("getIsSupport", "isSupport", "getSupport"),
        )
        hookCount += hookBooleanSetters(
            classNames = ARC_CONF_CLASSES,
            setterNames = setOf("setDisabled"),
            forcedValue = false,
        )
        hookCount += hookBooleanSetters(
            classNames = ARC_CONF_CLASSES,
            setterNames = setOf("setIsSupport", "setSupport"),
            forcedValue = true,
        )

        log("Installed $hookCount video feature hook(s)")
    }

    private fun hookBooleanAccessors(
        classNames: List<String>,
        falseMethods: Set<String>,
        trueMethods: Set<String>,
    ): Int {
        var count = 0
        classNames.mapNotNull { HostAccess.findClass(classLoader, it) }
            .distinct()
            .forEach { type ->
                HostAccess.methods(type)
                    .filter { method ->
                        method.parameterCount == 0 &&
                            method.returnType == Boolean::class.javaPrimitiveType &&
                            (method.name in falseMethods || method.name in trueMethods)
                    }
                    .forEach { method ->
                        hookConstantBoolean(method, method.name in trueMethods)
                        count++
                    }
            }
        return count
    }

    private fun hookBooleanSetters(
        classNames: List<String>,
        setterNames: Set<String>,
        forcedValue: Boolean,
    ): Int {
        var count = 0
        classNames.mapNotNull { HostAccess.findClass(classLoader, it) }
            .distinct()
            .forEach { type ->
                HostAccess.methods(type)
                    .filter { method ->
                        method.name in setterNames &&
                            method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
                    }
                    .forEach { method ->
                        xposed.hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept { chain ->
                                if (ModuleSettings.isUnlockVideoFeaturesEnabled(prefs)) {
                                    chain.proceed(arrayOf<Any>(forcedValue))
                                } else {
                                    chain.proceed()
                                }
                            }
                        count++
                    }
            }
        return count
    }

    private fun hookConstantBoolean(method: Method, value: Boolean) {
        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                if (ModuleSettings.isUnlockVideoFeaturesEnabled(prefs)) value else chain.proceed()
            }
    }

    private companion object {
        private val TRIAL_CLASSES = listOf(
            "com.bapis.bilibili.pgc.gateway.player.v2.SceneControl",
            "com.bapis.bilibili.playershared.VideoVod",
        )
        private val STREAM_INFO_CLASSES = listOf(
            "com.bapis.bilibili.app.playurl.v1.StreamInfo",
            "com.bapis.bilibili.playershared.StreamInfo",
        )
        private val ARC_CONF_CLASSES = listOf(
            "com.bapis.bilibili.playershared.ArcConf",
            "com.bapis.bilibili.app.playerunite.v1.ArcConf",
        )
    }
}
