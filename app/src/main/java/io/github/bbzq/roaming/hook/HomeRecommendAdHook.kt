package io.github.bbzq.roaming.hook

import io.github.bbzq.ModuleSettings
import io.github.bbzq.ModuleSettingsBridge
import io.github.bbzq.roaming.BaseRoamingHook
import io.github.bbzq.roaming.MethodHookParam
import io.github.bbzq.roaming.RoamingEnv
import io.github.bbzq.roaming.allFields
import io.github.bbzq.roaming.from
import io.github.bbzq.roaming.hookAfter
import io.github.bbzq.roaming.methodsNamed
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class HomeRecommendAdHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private var fieldWriteFailedLogged = false

    override fun startHook() {
        if (env.processName != env.packageName) return
        val enabled = ModuleSettings.isPurifyHomeRecommendAdEnabled(prefs)
        if (!enabled) {
            log("startHook: HomeRecommendAd disabled, provider=${ModuleSettingsBridge.lastProviderStatus}")
            return
        }

        val responseClass = PEGASUS_RESPONSE.from(classLoader)
        val holderDataClass = PEGASUS_HOLDER_DATA.from(classLoader)
        if (responseClass == null || holderDataClass == null) {
            log("startHook: HomeRecommendAd missing response=$responseClass holderData=$holderDataClass")
            return
        }

        val getItems = responseClass.methodsNamed("getItems")
            .firstOrNull {
                it.parameterCount == 0 &&
                    List::class.java.isAssignableFrom(it.returnType) &&
                    !Modifier.isStatic(it.modifiers) &&
                    !Modifier.isAbstract(it.modifiers)
            }
        val getHolderType = holderDataClass.methodsNamed("getHolderType")
            .firstOrNull {
                it.parameterCount == 0 &&
                    it.returnType == String::class.java &&
                    !Modifier.isStatic(it.modifiers)
            }
        if (getItems == null || getHolderType == null) {
            log("startHook: HomeRecommendAd no hook point found getItems=$getItems getHolderType=$getHolderType")
            return
        }

        val symbols = FilterSymbols(
            getHolderType = getHolderType,
            getHolderStyle = holderDataClass.methodsNamed("getHolderStyle")
                .firstOrNull { it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) },
            isSmallCard = HOLDER_STYLE.from(classLoader)
                ?.methodsNamed("isSmallCard")
                ?.firstOrNull { it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType },
            adInfoClass = AD_INFO.from(classLoader),
            itemsField = responseClass.allFields()
                .filter { List::class.java.isAssignableFrom(it.type) }
                .singleOrNull(),
        )

        env.hookAfter(getItems) { param ->
            val result = filterReturnList(param, symbols)
            if (result != null) {
                log(
                    "HomeRecommendAd removed ${result.removed} item(s) " +
                        "reasons=${result.reasonSummary()} " +
                        "from ${getItems.declaringClass.name}.${getItems.name}",
                )
            }
        }
        log("startHook: HomeRecommendAd at ${getItems.declaringClass.name}.${getItems.name}")
    }

    private fun filterReturnList(param: MethodHookParam, symbols: FilterSymbols): FilterResult? {
        val items = param.result as? List<*> ?: return null
        if (items.isEmpty()) return null

        val filtered = ArrayList<Any?>(items.size)
        val reasons = linkedMapOf<String, Int>()
        var removed = 0
        items.forEach { item ->
            val reason = removeReason(item, symbols)
            if (reason != null) {
                removed += 1
                reasons[reason] = (reasons[reason] ?: 0) + 1
            } else {
                filtered += item
            }
        }
        if (removed == 0) return null

        param.result = filtered
        writeBackFilteredItems(param.thisObject, symbols.itemsField, filtered)
        return FilterResult(filtered, removed, reasons)
    }

    private fun removeReason(item: Any?, symbols: FilterSymbols): String? {
        if (item == null) return null
        val holderType = holderTypeOf(item, symbols.getHolderType)
        if (holderType == BANNER_V8) return "banner_v8"
        if (symbols.adInfoClass?.isInstance(item) == true && isWideCard(item, symbols)) return "ad_card"
        return null
    }

    private fun holderTypeOf(item: Any, getHolderType: Method): String? =
        runCatching { getHolderType.invoke(item) as? String }.getOrNull()

    private fun isWideCard(item: Any, symbols: FilterSymbols): Boolean {
        val getHolderStyle = symbols.getHolderStyle ?: return true
        val isSmallCard = symbols.isSmallCard ?: return true
        val smallCard = runCatching {
            val style = getHolderStyle.invoke(item) ?: return@runCatching null
            isSmallCard.invoke(style) as? Boolean
        }.getOrNull()
        return smallCard != true
    }

    private fun writeBackFilteredItems(target: Any?, field: Field?, items: List<Any?>) {
        if (target == null || field == null) return
        runCatching {
            field.set(target, items)
        }.onFailure { throwable ->
            if (!fieldWriteFailedLogged) {
                fieldWriteFailedLogged = true
                log("HomeRecommendAd could not update PegasusResponse items field", throwable)
            }
        }
    }

    private data class FilterSymbols(
        val getHolderType: Method,
        val getHolderStyle: Method?,
        val isSmallCard: Method?,
        val adInfoClass: Class<*>?,
        val itemsField: Field?,
    )

    private data class FilterResult(
        val items: List<Any?>,
        val removed: Int,
        val reasons: Map<String, Int>,
    ) {
        fun reasonSummary(): String =
            reasons.entries.joinToString(",") { (reason, count) -> "$reason:$count" }
    }

    private companion object {
        private const val PEGASUS_RESPONSE = "com.bilibili.pegasus.data.base.PegasusResponse"
        private const val PEGASUS_HOLDER_DATA = "com.bilibili.pegasus.PegasusHolderData"
        private const val HOLDER_STYLE = "com.bilibili.pegasus.HolderStyle"
        private const val AD_INFO = "com.bilibili.adcommon.data.IAdInfo"
        private const val BANNER_V8 = "banner_v8"
    }
}
