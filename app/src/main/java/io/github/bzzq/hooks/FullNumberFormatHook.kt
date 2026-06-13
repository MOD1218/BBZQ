package io.github.bzzq.hooks

import io.github.bzzq.ModuleSettings
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class FullNumberFormatHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    override fun startHook() {
        val method = HostMethodResolver(context).resolve(
            cacheKey = "number_format_shorten",
            fixedCandidates = {
                STABLE_CLASS_NAMES.asSequence()
                    .mapNotNull { HostAccess.findClass(classLoader, it) }
                    .flatMap(HostAccess::methods)
            },
            searchPackages = listOf("com.bilibili", "tv.danmaku"),
            usingStrings = listOf("万", "亿"),
            validate = ::isFormatterMethod,
        )

        if (method == null) {
            log("NumberFormat shorten method not found — full-number hook skipped")
            return
        }

        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                if (!ModuleSettings.isFullNumberFormatEnabled(prefs)) {
                    return@intercept chain.proceed()
                }
                val number = when (val raw = chain.args[0]) {
                    is Long -> raw
                    is Int -> raw.toLong()
                    else -> return@intercept chain.proceed()
                }
                if (number >= 0) number.toString() else chain.proceed()
            }

        log("Installed full-number hook at ${method.declaringClass.name}#${method.name}")
    }

    private fun isFormatterMethod(method: Method): Boolean {
        if (!Modifier.isStatic(method.modifiers)) return false
        if (method.returnType != String::class.java) return false
        val params = method.parameterTypes
        if (params.isEmpty() || params.size > 2) return false
        val firstParam = params[0]
        if (
            firstParam != Long::class.javaPrimitiveType &&
            firstParam != Int::class.javaPrimitiveType
        ) {
            return false
        }

        return runCatching {
            val args: Array<Any> = if (params.size == 1) {
                if (firstParam == Int::class.javaPrimitiveType) {
                    arrayOf(10000)
                } else {
                    arrayOf(10000L)
                }
            } else {
                if (firstParam == Int::class.javaPrimitiveType) {
                    arrayOf(10000, "")
                } else {
                    arrayOf(10000L, "")
                }
            }
            val result = method.invoke(null, *args) as? String
            result?.contains("万") == true
        }.getOrDefault(false)
    }

    private companion object {
        private val STABLE_CLASS_NAMES = listOf(
            "com.bilibili.lib.utils.NumberFormat",
            "com.bilibili.foundation.utils.NumberFormat",
        )
    }
}
