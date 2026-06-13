package io.github.bzzq.hooks

import android.content.Context
import io.github.bzzq.AccessKeyRepository
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class AccessKeyCaptureHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    override fun startHook() {
        val accountMarker = HostMethodResolver(context).resolve(
            cacheKey = "bili_accounts_class_marker",
            fixedCandidates = { emptySequence() },
            searchPackages = listOf("com.bilibili", "tv.danmaku"),
            usingStrings = listOf("logout with account exception"),
            validate = ::isAccountClassMarker,
        )
        if (accountMarker == null) {
            log("BiliAccounts class not found - access_key reader unavailable")
            return
        }

        val accountClass = accountMarker.declaringClass
        val getAccount = HostAccess.methods(accountClass).firstOrNull(::isAccountFactory)
        if (getAccount == null) {
            log("BiliAccounts factory not found - access_key reader unavailable")
            return
        }

        val stringGetters = HostAccess.methods(accountClass)
            .filter(::isStringGetter)
            .sortedByDescending(::getterPriority)
            .toList()
        AccessKeyRepository.register {
            val application = HostEnv.currentApplication() ?: return@register null
            val account = runCatching { getAccount.invoke(null, application) }.getOrNull()
                ?: return@register null
            stringGetters.asSequence()
                .mapNotNull { getter ->
                    runCatching { getter.invoke(account) as? String }.getOrNull()
                }
                .firstOrNull(AccessKeyRepository::looksLikeAccessKey)
        }
        AccessKeyRepository.read(prefs)
        log("Installed live access_key reader at ${accountClass.name} (${stringGetters.size} getter candidates)")
    }

    private fun isAccountClassMarker(method: Method): Boolean =
        method.parameterCount == 1 &&
            HostAccess.methods(method.declaringClass).any(::isAccountFactory)

    private fun isAccountFactory(method: Method): Boolean =
        Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.contentEquals(arrayOf(Context::class.java)) &&
            method.returnType == method.declaringClass

    private fun isStringGetter(method: Method): Boolean =
        !Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 0 &&
            method.returnType == String::class.java

    private fun getterPriority(method: Method): Int = when {
        method.name.contains("access", ignoreCase = true) -> 2
        method.name.contains("token", ignoreCase = true) -> 1
        else -> 0
    }
}
