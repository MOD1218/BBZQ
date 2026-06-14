package io.github.bbzq.hooks

import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method

internal class HostMethodResolver(
    private val context: HookContext,
) {
    fun resolve(
        cacheKey: String,
        fixedCandidates: () -> Sequence<Method>,
        searchPackages: List<String> = listOf("com.bilibili", "tv.danmaku.bili"),
        usingStrings: List<String> = emptyList(),
        validate: (Method) -> Boolean,
    ): Method? {
        context.dexDesc(cacheKey).toMethodOrNull()
            ?.takeIf(validate)
            ?.let { return it }

        fixedCandidates().firstOrNull(validate)?.let { method ->
            cache(cacheKey, method)
            return method
        }

        if (usingStrings.isEmpty()) return null
        val bridge = context.dexKitOrNull() ?: return null
        val result = runCatching {
            bridge.findMethod {
                searchPackages(searchPackages)
                findFirst = false
                matcher {
                    usingStrings(usingStrings, StringMatchType.Contains, false)
                }
            }
        }.getOrElse {
            context.log("DexKit query failed for $cacheKey", it)
            return null
        }

        return result.asSequence()
            .mapNotNull { data ->
                runCatching { data.getMethodInstance(context.classLoader) }.getOrNull()
            }
            .firstOrNull(validate)
            ?.also { cache(cacheKey, it) }
    }

    private fun cache(key: String, method: Method) {
        context.dexKitCache().saveMethodCache(key, org.luckypray.dexkit.wrap.DexMethod(method).serialize())
    }
}
