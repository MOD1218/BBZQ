package io.github.bbzq.hooks

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import io.github.bbzq.ModuleSettingsNavigator
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Injects an entry into the "Mine" (User Center) page.
 * Similar to upstream roaming modules: add an item to the list.
 */
class MinePageEntryHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {

    override fun startHook() {
        hookMinePageEntry()
        hookRouter()
    }

    private fun hookMinePageEntry() {
        val addEntryMethod = HostMethodResolver(context).resolve(
            cacheKey = "mine_page_add_entry_legacy",
            fixedCandidates = { emptySequence() },
            searchPackages = listOf("com.bilibili", "tv.danmaku"),
            usingStrings = listOf("main.my-information.noportrait.0.show"),
            validate = { method ->
                method.parameterTypes.any { List::class.java.isAssignableFrom(it) }
            },
        )

        if (addEntryMethod != null) {
            xposed.hook(addEntryMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    runCatching { injectEntry(chain.args) }
                        .onFailure { log("Failed to inject mine page entry (legacy path)", it) }
                    result
                }
            log("Mine page: using legacy addSetting hook path")
            return
        }

        log("Mine page: legacy path not found, trying adapter path (8.97.0+)")
        hookMinePageViaAdapter()
    }

    private fun hookMinePageViaAdapter() {
        val homeUserCenterClass = HostMethodResolver(context).resolve(
            cacheKey = "mine_page_home_user_center_anchor",
            fixedCandidates = { emptySequence() },
            searchPackages = listOf("com.bilibili", "tv.danmaku"),
            usingStrings = listOf("main.my-information.noportrait.0.show"),
            validate = { method ->
                HostAccess.fields(method.declaringClass).any { !Modifier.isStatic(it.modifiers) }
            },
        )?.declaringClass ?: run {
            log("Mine page: HomeUserCenter class not found — adapter path skipped")
            return
        }

        val adapterType = HostAccess.findClass(
            classLoader,
            "androidx.recyclerview.widget.RecyclerView\$Adapter",
            "android.support.v7.widget.RecyclerView\$Adapter",
        ) ?: run {
            log("Mine page: RecyclerView.Adapter class not found")
            return
        }

        val mineAdapterClass = HostAccess.fields(homeUserCenterClass)
            .firstOrNull { field ->
                !Modifier.isStatic(field.modifiers) && adapterType.isAssignableFrom(field.type)
            }
            ?.type ?: run {
            log("Mine page: mineAdapter field not found in ${homeUserCenterClass.name}")
            return
        }
        log("Mine page: found adapter class ${mineAdapterClass.name}")

        val adapterDataField = HostAccess.fields(mineAdapterClass)
            .firstOrNull { field ->
                !Modifier.isStatic(field.modifiers) && List::class.java.isAssignableFrom(field.type)
            } ?: run {
            log("Mine page: adapter data field not found in ${mineAdapterClass.name}")
            return
        }

        val getItemCount = HostAccess.methods(mineAdapterClass)
            .firstOrNull { method ->
                method.name == "getItemCount" &&
                    method.parameterCount == 0 &&
                    method.returnType == Int::class.javaPrimitiveType
            } ?: run {
            log("Mine page: getItemCount not found in ${mineAdapterClass.name}")
            return
        }

        xposed.hook(getItemCount)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                runCatching { injectEntryFromAdapter(chain.thisObject, adapterDataField) }
                    .onFailure { log("Failed to inject mine page entry (adapter path)", it) }
                chain.proceed()
            }

        log("Mine page: adapter path installed (8.97.0+ mode)")
    }

    private fun injectEntry(args: Iterable<Any?>) {
        val list = args.firstNotNullOfOrNull { HostAccess.asMutableList(it) }
            ?: args.firstNotNullOfOrNull { arg ->
                arg?.let { HostAccess.get(it, "moreSectionList") }?.let { HostAccess.asMutableList(it) }
            } ?: return
        if (hasEntry(list)) return
        createEntryItem()?.let(list::add)
    }

    private fun injectEntryFromAdapter(adapter: Any, dataField: Field) {
        val groups = HostAccess.asIterable(runCatching { dataField.get(adapter) }.getOrNull())
        val itemLists = groups.mapNotNull { group ->
            group?.let { HostAccess.asMutableList(HostAccess.get(it, "itemList")) }
        }.toList()
        if (itemLists.isEmpty()) return
        if (itemLists.any(::hasEntry)) return
        val targetList = itemLists.last()
        createEntryItem()?.let(targetList::add)
    }

    private fun hasEntry(items: Iterable<Any?>): Boolean =
        items.any { item ->
            item != null &&
                (
                    HostAccess.get(item, "uri") == SETTINGS_URI ||
                        HostAccess.get(item, "title") == ENTRY_TITLE
                )
        }

    private fun createEntryItem(): Any? {
        val itemClass = HostAccess.findClass(classLoader, "com.bilibili.lib.homepage.mine.MenuGroup\$Item")
            ?: return null
        val item = runCatching {
            itemClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }.getOrNull() ?: return null
        HostAccess.set(item, ENTRY_ITEM_ID, "id")
        HostAccess.set(item, ENTRY_TITLE, "title")
        HostAccess.set(item, ENTRY_SUMMARY, "summary")
        HostAccess.set(item, ENTRY_ICON, "icon")
        HostAccess.set(item, SETTINGS_URI, "uri")
        HostAccess.set(item, 1, "visible")
        return item
    }

    private fun hookRouter() {
        runCatching {
            val startActivityMethods = Instrumentation::class.java.declaredMethods
                .filter { method ->
                    method.name == "execStartActivity" &&
                        method.parameterTypes.any { it == Intent::class.java }
                }
                .distinctBy { it.toGenericString() }

            startActivityMethods.forEach { method ->
                xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val intent = chain.args.firstNotNullOfOrNull { it as? Intent }
                            ?: return@intercept chain.proceed()
                        if (intent.data?.toString() != SETTINGS_URI) {
                            return@intercept chain.proceed()
                        }

                        val activity = chain.args.firstNotNullOfOrNull { it as? Activity }
                            ?: return@intercept chain.proceed()
                        ModuleSettingsNavigator.open(activity)
                        null
                    }
            }

            if (startActivityMethods.isEmpty()) {
                log("Mine page router hook skipped: no execStartActivity overload found")
            } else {
                log("Installed mine page router hook for URI: $SETTINGS_URI")
            }
        }.onFailure {
            log("Failed to install mine page router hook", it)
        }
    }

    private companion object {
        private const val SETTINGS_URI = "bilibili://bbzq/settings"
        private const val ENTRY_ITEM_ID = 0x517700
        private const val ENTRY_TITLE = "高级设置"
        private const val ENTRY_SUMMARY = "BBZQ 设置"
        private const val ENTRY_ICON =
            "https://i0.hdslb.com/bfs/album/276769577d2a5db1d9f914364abad7c5253086f6.png"
    }
}
