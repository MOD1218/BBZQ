package io.github.bzzq.hooks

import io.github.bzzq.ModuleSettings
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

class AccessKeyCaptureHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    @Volatile
    private var lastSavedKey: String? = null

    override fun startHook() {
        val methods = mutableSetOf<Method>()
        TOKEN_CLASSES.mapNotNull { HostAccess.findClass(classLoader, it) }
            .forEach { type ->
                HostAccess.methods(type)
                    .filter { it.parameterCount == 0 && it.returnType == String::class.java }
                    .filterTo(methods) {
                        it.name.contains("access", true) || it.name.contains("token", true)
                    }
            }

        val accountMarker = HostMethodResolver(context).resolve(
            cacheKey = "bili_accounts_marker",
            fixedCandidates = { emptySequence() },
            usingStrings = listOf("logout with account exception"),
            validate = { true },
        )
        accountMarker?.declaringClass?.let { accountClass ->
            HostAccess.methods(accountClass)
                .filter { it.parameterCount == 0 && it.returnType == String::class.java }
                .forEach(methods::add)
        }

        methods.forEach { method ->
            xposed.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    (result as? String)?.takeIf(::looksLikeAccessKey)?.let(::save)
                    result
                }
        }
        log("Installed access_key capture on ${methods.size} method(s)")
    }

    private fun looksLikeAccessKey(value: String): Boolean =
        value.length in 24..128 &&
            value.none(Char::isWhitespace) &&
            value.any(Char::isDigit) &&
            value.any(Char::isLetter)

    private fun save(value: String) {
        if (lastSavedKey == value) return
        lastSavedKey = value
        prefs.edit().putString(ModuleSettings.KEY_LAST_ACCESS_KEY, value).apply()
        log("access_key captured (length=${value.length})")
    }

    private companion object {
        private val TOKEN_CLASSES = listOf(
            "com.bilibili.nativelibrary.BiliBiliToken",
            "com.bilibili.lib.login.model.AccessToken",
        )
    }
}
