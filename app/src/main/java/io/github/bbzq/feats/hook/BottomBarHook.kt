package io.github.bbzq.feats.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allFields
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import org.json.JSONObject

class BottomBarHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private var cachedBottomEntriesLoaded = false
    private var cacheReaderFailureLogged = false

    override fun startHook() {
        ModuleSettings.refreshKnownBottomBarItemsCache(prefs)
        saveCachedBottomEntries()
        Handler(Looper.getMainLooper()).postDelayed(
            { saveCachedBottomEntries() },
            HOME_TAB_CACHE_READ_DELAY_MS,
        )
        val symbols = env.symbols?.bottomBar?.restore(classLoader)
        val tabHostSetTabsMethods = symbols?.tabHostSetTabsMethods.orEmpty()
        val tabHostGetTabsMethods = symbols?.tabHostGetTabsMethods.orEmpty()
        val baseOnViewCreatedMethods = symbols?.baseOnViewCreatedMethods.orEmpty()

        tabHostSetTabsMethods.forEach { method ->
            env.hookBefore(method) { param ->
                runCatching {
                    val tabs = param.args.getOrNull(0) as? List<*> ?: return@runCatching
                    dispatch(tabs)
                }.onFailure {
                    log("Bottom bar TabHost processor failed at ${method.declaringClass.name}.${method.name}", it)
                }
            }
            env.hookAfter(method) { param ->
                runCatching {
                    val tabs = param.args.getOrNull(0) as? List<*> ?: return@runCatching
                    hideTabs(param.thisObject, tabs)
                }.onFailure {
                    log("Bottom bar TabHost post processor failed at ${method.declaringClass.name}.${method.name}", it)
                }
            }
        }

        val tabHostClass = tabHostSetTabsMethods.firstOrNull()?.declaringClass
        baseOnViewCreatedMethods.forEach { method ->
            env.hookAfter(method) { param ->
                runCatching {
                    val fragment = param.thisObject ?: return@runCatching
                    saveCachedBottomEntries()
                    fragment.extractSourceBottomEntries()
                        .takeIf { it.isNotEmpty() }
                        ?.let { entries ->
                            saveKnownEntries(
                                entries,
                                preserveMissing = shouldPreserveMissing(entries.map(BottomBarEntry::id)),
                            )
                        }
                    val host = fragment.findTabHost(tabHostClass) ?: return@runCatching
                    val getTabs = tabHostGetTabsMethods.firstOrNull { it.declaringClass.isInstance(host) }
                        ?: return@runCatching
                    val tabs = getTabs.invoke(host) as? List<*> ?: return@runCatching
                    dispatch(tabs)
                    hideTabs(host, tabs)
                }.onFailure {
                    log("Bottom bar onViewCreated processor failed at ${method.declaringClass.name}.${method.name}", it)
                }
            }
        }

        val totalMethods = tabHostSetTabsMethods.size + baseOnViewCreatedMethods.size
        if (totalMethods == 0) {
            log("startHook: BottomBar, no hook point found")
        } else {
            log("startHook: BottomBar, methods=$totalMethods")
        }
    }

    private fun dispatch(tabs: List<*>) {
        saveCachedBottomEntries()
        val hiddenIds = ModuleSettings.getHiddenBottomBarItems(prefs)
        val knownItems = linkedSetOf<String>()
        val observedIds = linkedSetOf<String>()

        tabs.forEach { item ->
            val entry = item?.extractBottomEntry()
            if (entry == null) return@forEach
            observedIds += entry.id
            knownItems += encodeBottomItem(
                order = knownItems.size,
                id = entry.id,
                name = entry.name,
                uri = entry.uri,
            )
        }

        saveKnownItems(knownItems, preserveMissing = hiddenIds.any { it !in observedIds })
    }

    private fun hideTabs(host: Any?, tabs: List<*>) {
        val container = host?.findTabContainer(tabs.size) ?: return
        val hiddenIds = if (ModuleSettings.isCustomBottomBarEnabled(prefs)) {
            ModuleSettings.getHiddenBottomBarItems(prefs)
        } else {
            emptySet()
        }

        tabs.forEachIndexed { index, item ->
            val entry = item?.extractBottomEntry() ?: return@forEachIndexed
            val child = container.getChildAt(index) ?: return@forEachIndexed
            val hidden = entry.id in hiddenIds
            child.visibility = if (hidden) View.GONE else View.VISIBLE
            child.isClickable = !hidden
            child.isEnabled = !hidden
            child.alpha = if (hidden) 0f else 1f
        }
    }

    private fun Any.findTabContainer(tabCount: Int): ViewGroup? =
        javaClass.allFields()
            .mapNotNull { field ->
                runCatching { field.get(this) as? LinearLayout }.getOrNull()
            }
            .firstOrNull { container -> container.childCount >= tabCount }

    private fun saveCachedBottomEntries() {
        if (cachedBottomEntriesLoaded) return
        runCatching {
            val file = env.hostContext.filesDir.resolve(HOME_TAB_CACHE_FILE)
            if (!file.isFile) return
            val bottom = JSONObject(file.readText()).optJSONObject("data")?.optJSONArray("bottom") ?: return
            val entries = ArrayList<BottomBarEntry>(bottom.length())
            for (index in 0 until bottom.length()) {
                val item = bottom.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim()
                val uri = item.optString("uri").trim()
                if (id.isNotBlank() && name.isNotBlank()) {
                    entries += BottomBarEntry(id, name, uri)
                }
            }
            if (entries.size >= MIN_BOTTOM_BAR_ITEMS) {
                saveKnownEntries(entries, preserveMissing = false)
                cachedBottomEntriesLoaded = true
            }
        }.onFailure {
            if (!cacheReaderFailureLogged) {
                cacheReaderFailureLogged = true
                log("Bottom bar cache reader failed", it)
            }
        }
    }

    private fun Any.extractBottomEntry(): BottomBarEntry? {
        val strings = javaClass.allFields()
            .mapNotNull { field ->
                runCatching { field.get(this) as? String }.getOrNull()?.trim()
            }
            .filter { it.isNotEmpty() }
            .distinct()

        val guessedUri = strings.firstOrNull(::looksLikeUri)
        val guessedName = strings.firstOrNull(::looksLikeDisplayName)
        val guessedId = strings.firstOrNull { it != guessedUri && it != guessedName && looksLikeAsciiId(it) }
            ?: strings.firstOrNull { it != guessedUri && it != guessedName && looksLikeBottomBarId(it) }
            ?: strings.firstOrNull { it != guessedUri && it != guessedName }

        if (guessedId == null && guessedName == null && guessedUri == null) return null
        val resolvedId = guessedId ?: guessedName ?: guessedUri ?: return null
        val resolvedName = guessedName ?: return null
        return BottomBarEntry(resolvedId, resolvedName, guessedUri.orEmpty())
    }

    private fun Any.extractSourceBottomEntries(): List<BottomBarEntry> =
        javaClass.allFields()
            .mapNotNull { field ->
                runCatching { field.get(this) as? List<*> }.getOrNull()
            }
            .mapNotNull { list ->
                list.mapNotNull { item -> item?.extractBottomEntryDeep() }
                    .takeIf { entries ->
                        entries.size == list.size &&
                            entries.size >= MIN_BOTTOM_BAR_ITEMS &&
                            entries.all { it.uri.isNotBlank() }
                    }
            }
            .maxByOrNull { it.size }
            .orEmpty()

    private fun Any.extractBottomEntryDeep(): BottomBarEntry? =
        extractBottomEntry()
            ?: javaClass.allFields()
                .mapNotNull { field ->
                    runCatching { field.get(this) }.getOrNull()
                }
                .firstNotNullOfOrNull { value ->
                    value.takeUnless { it === this || it is Collection<*> || it.javaClass.isArray }
                        ?.extractBottomEntry()
                }

    private fun Any.findTabHost(tabHostClass: Class<*>?): Any? {
        if (tabHostClass == null) return null
        if (tabHostClass.isInstance(this)) return this
        return javaClass.allFields()
            .firstNotNullOfOrNull { field ->
                runCatching { field.get(this) }
                    .getOrNull()
                    ?.takeIf(tabHostClass::isInstance)
            }
    }

    private fun looksLikeUri(value: String): Boolean =
        "://" in value || value.startsWith("bilibili://", ignoreCase = true) ||
            value.startsWith("activity://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)

    private fun looksLikeDisplayName(value: String): Boolean {
        if (looksLikeUri(value)) return false
        if (value.length < 2) return false
        return value.any { it.isLetter() || it.code in 0x4E00..0x9FFF }
    }

    private fun looksLikeBottomBarId(value: String): Boolean {
        if (looksLikeUri(value)) return false
        if (value.length !in 1..48) return false
        if (value.any(Char::isWhitespace)) return false
        return value.any { it.isLetterOrDigit() || it == '_' || it == '-' }
    }

    private fun looksLikeAsciiId(value: String): Boolean {
        if (!looksLikeBottomBarId(value)) return false
        return value.all { it.code in 0x21..0x7E }
    }

    private fun saveKnownEntries(entries: Collection<BottomBarEntry>, preserveMissing: Boolean) {
        val items = entries.mapIndexedTo(linkedSetOf()) { index, entry ->
            encodeBottomItem(
                order = index,
                id = entry.id,
                name = entry.name,
                uri = entry.uri,
            )
        }
        saveKnownItems(items, preserveMissing)
    }

    private fun shouldPreserveMissing(observedIds: Collection<String>): Boolean {
        val observed = observedIds.toSet()
        return ModuleSettings.getHiddenBottomBarItems(prefs).any { it !in observed }
    }

    private fun saveKnownItems(items: Set<String>, preserveMissing: Boolean) {
        if (items.isEmpty()) return
        val oldItems = ModuleSettings.getKnownBottomBarItems(prefs)
        val updatedItems = if (preserveMissing) mergeKnownItems(oldItems, items) else normalizeKnownItems(items)
        if (oldItems == updatedItems) return
        ModuleSettings.cacheKnownBottomBarItems(updatedItems)
        prefs.edit()
            .putStringSet(ModuleSettings.KEY_KNOWN_BOTTOM_BAR_ITEMS, updatedItems.toMutableSet())
            .apply()
    }

    private fun mergeKnownItems(oldItems: Set<String>, observedItems: Set<String>): Set<String> {
        val merged = linkedMapOf<String, KnownBottomBarItem>()
        oldItems.mapNotNull(::decodeBottomItem).forEach { item ->
            merged.putIfAbsent(item.id, item)
        }
        observedItems.mapNotNull(::decodeBottomItem).forEach { item ->
            val oldItem = merged[item.id]
            merged[item.id] = item.copy(order = oldItem?.order ?: item.order)
        }
        return encodeKnownItems(merged.values)
    }

    private fun normalizeKnownItems(items: Set<String>): Set<String> =
        encodeKnownItems(items.mapNotNull(::decodeBottomItem))

    private fun encodeKnownItems(items: Collection<KnownBottomBarItem>): Set<String> =
        items.asSequence()
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .distinctBy(KnownBottomBarItem::id)
            .sortedWith(compareBy<KnownBottomBarItem> { it.order }.thenBy { it.name }.thenBy { it.id })
            .mapIndexed { index, item ->
                encodeBottomItem(
                    order = index,
                    id = item.id,
                    name = item.name,
                    uri = item.uri,
                )
            }
            .toMutableSet()

    private fun decodeBottomItem(raw: String): KnownBottomBarItem? {
        val parts = raw.split(ITEM_SEPARATOR, limit = 4)
        if (parts.size == 4) {
            val order = parts[0].toIntOrNull() ?: return null
            return KnownBottomBarItem(order, parts[1], parts[2], parts[3])
        }
        if (parts.size == 3) {
            return KnownBottomBarItem(Int.MAX_VALUE, parts[0], parts[1], parts[2])
        }
        return null
    }

    private fun encodeBottomItem(order: Int, id: String, name: String, uri: String): String =
        listOf(order.toString(), id, name, uri)
            .joinToString(ITEM_SEPARATOR) { it.sanitizeItemPart() }

    private fun String.sanitizeItemPart(): String =
        replace('\t', ' ')
            .replace('\n', ' ')
            .replace('\r', ' ')

    private data class BottomBarEntry(
        val id: String,
        val name: String,
        val uri: String,
    )

    private data class KnownBottomBarItem(
        val order: Int,
        val id: String,
        val name: String,
        val uri: String,
    )

    private companion object {
        private const val ITEM_SEPARATOR = "\t"
        private const val MIN_BOTTOM_BAR_ITEMS = 3
        private const val HOME_TAB_CACHE_FILE = "home_tab_v2.data"
        private const val HOME_TAB_CACHE_READ_DELAY_MS = 2_000L
    }
}
