package io.github.bbzq.feats.hook

import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allFields
import io.github.bbzq.feats.getObjectField
import io.github.bbzq.feats.hookAfter

class BottomBarHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        ModuleSettings.refreshKnownBottomBarItemsCache(prefs)
        val symbols = env.symbols?.bottomBar?.restore(classLoader)
        val parserMethods = symbols?.parserMethods.orEmpty()
        val resourceMethods = symbols?.resourceMethods.orEmpty()

        parserMethods.forEach { method ->
            env.hookAfter(method) { param ->
                runCatching {
                    dispatch(param.result)?.let { updated ->
                        if (updated !== param.result) {
                            param.result = updated
                        }
                    }
                }
                    .onFailure {
                        log("Bottom bar processor failed at ${method.declaringClass.name}.${method.name}", it)
                    }
                }
        }

        resourceMethods.forEach { method ->
            env.hookAfter(method) { param ->
                runCatching {
                    dispatch(param.result)?.let { updated ->
                        if (updated !== param.result) {
                            param.result = updated
                        }
                    }
                }
                    .onFailure {
                        log("Bottom bar resource processor failed at ${method.declaringClass.name}.${method.name}", it)
                    }
            }
        }

        val totalMethods = parserMethods.size + resourceMethods.size
        if (totalMethods == 0) {
            log("startHook: BottomBar, no hook point found")
        } else {
            log("startHook: BottomBar, methods=$totalMethods")
        }
    }

    private fun dispatch(rawResult: Any?): Any? {
        val result = unwrap(rawResult) ?: return null
        val bottom = when (result) {
            is MutableList<*> -> {
                @Suppress("UNCHECKED_CAST")
                result as MutableList<Any?>
            }

            is List<*> -> result.toMutableList() as MutableList<Any?>

            else -> {
                if (!result.isTabResponse()) return null
                result.extractBottomItems() ?: return null
            }
        }

        val hiddenIds = ModuleSettings.getHiddenBottomBarItems(prefs)
        val enabled = ModuleSettings.isCustomBottomBarEnabled(prefs)
        val knownItems = linkedSetOf<String>()

        bottom.removeAll { item ->
            val entry = item?.extractBottomEntry() ?: return@removeAll false

            knownItems += encodeBottomItem(
                order = knownItems.size,
                id = entry.id,
                name = entry.name,
                uri = entry.uri,
            )
            enabled && entry.id in hiddenIds
        }

        saveKnownItems(knownItems)
        return if (result is List<*> && result !is MutableList<*>) bottom else result
    }

    private fun unwrap(result: Any?): Any? {
        if (result == null) return null
        val className = result.javaClass.name
        return if (className.endsWith("GeneralResponse") || className.endsWith("RxGeneralResponse")) {
            result.getObjectField("data")
        } else {
            result
        }
    }

    private fun Any.isTabResponse(): Boolean {
        val className = javaClass.name
        if (className in TAB_RESPONSE_CLASSES || className.endsWith("MainResourceManager\$TabResponse")) {
            return true
        }
        return className.endsWith("TabResponse") || javaClass.allFields().any { field ->
            field.name == "tabData" || field.name == "bottom" || field.name == "items"
        }
    }

    private fun Any.extractBottomItems(): MutableList<Any?>? {
        readAny("tabData", "data")?.readMutableList("bottom", "items")?.let { return it }
        readMutableList("bottom", "items")?.let { return it }
        return findBottomItemsDeep()
    }

    private fun Any.findBottomItemsDeep(
        visited: MutableSet<Int> = linkedSetOf(),
        depth: Int = 3,
    ): MutableList<Any?>? {
        if (depth < 0) return null
        val identity = System.identityHashCode(this)
        if (!visited.add(identity)) return null

        javaClass.allFields().forEach { field ->
            val value = runCatching { field.get(this) }.getOrNull() ?: return@forEach
            when (value) {
                is MutableList<*> -> {
                    if (value.any { it?.extractBottomEntry() != null }) {
                        @Suppress("UNCHECKED_CAST")
                        return value as MutableList<Any?>
                    }
                }

                is List<*> -> {
                    if (value.any { it?.extractBottomEntry() != null }) {
                        @Suppress("UNCHECKED_CAST")
                        return value as? MutableList<Any?>
                    }
                }

                else -> {
                    if (depth == 0 || field.type.isPrimitive || isLeafType(field.type)) return@forEach
                    (value as? Any)?.findBottomItemsDeep(visited, depth - 1)?.let { return it }
                }
            }
        }

        return null
    }

    private fun Any.extractBottomEntry(): BottomBarEntry? {
        val id = readString("tabId", "f498840tabId", "id")?.takeIf { it.isNotBlank() }
        val name = readString("name", "f498841name", "title")?.takeIf { it.isNotBlank() }
        val uri = readString("uri", "f498844uri", "jumpUrl")?.takeIf { it.isNotBlank() }
        if (id != null || name != null || uri != null) {
            val resolvedId = id ?: name ?: uri ?: return null
            val resolvedName = name ?: id ?: uri ?: resolvedId
            return BottomBarEntry(resolvedId, resolvedName, uri.orEmpty())
        }

        val strings = javaClass.allFields()
            .mapNotNull { field ->
                runCatching { field.get(this) as? String }.getOrNull()?.trim()
            }
            .filter { it.isNotEmpty() }
            .distinct()

        val guessedUri = strings.firstOrNull(::looksLikeUri)
        val guessedName = strings.firstOrNull(::looksLikeDisplayName)
            ?: strings.firstOrNull { it != guessedUri && it.length > 1 }
        val guessedId = strings.firstOrNull(::looksLikeBottomBarId)
            ?: strings.firstOrNull { it != guessedUri && it != guessedName }

        if (guessedId == null && guessedName == null && guessedUri == null) return null
        val resolvedId = guessedId ?: guessedName ?: guessedUri ?: return null
        val resolvedName = guessedName ?: guessedId ?: guessedUri ?: resolvedId
        return BottomBarEntry(resolvedId, resolvedName, guessedUri.orEmpty())
    }

    private fun Any.readAny(vararg names: String): Any? =
        names.firstNotNullOfOrNull { name -> getObjectField(name) }

    private fun Any.readString(vararg names: String): String? =
        readAny(*names)?.toString()

    @Suppress("UNCHECKED_CAST")
    private fun Any.readMutableList(vararg names: String): MutableList<Any?>? {
        names.forEach { name ->
            val value = getObjectField(name) as? MutableList<Any?>
            if (value != null) return value
        }
        return null
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

    private fun isLeafType(type: Class<*>): Boolean =
        type.name.startsWith("java.") ||
            type.name.startsWith("kotlin.") ||
            type.name.startsWith("android.") ||
            type.name.startsWith("androidx.") ||
            type.isEnum

    private fun saveKnownItems(items: Set<String>) {
        if (items.isEmpty()) return
        val oldItems = ModuleSettings.getKnownBottomBarItems(prefs)
        if (oldItems == items) return
        ModuleSettings.cacheKnownBottomBarItems(items)
        prefs.edit()
            .putStringSet(ModuleSettings.KEY_KNOWN_BOTTOM_BAR_ITEMS, items.toMutableSet())
            .apply()
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

    private companion object {
        private const val ITEM_SEPARATOR = "\t"
        private val TAB_RESPONSE_CLASSES = setOf(
            "tv.danmaku.bili.ui.main2.resource.MainResourceManager\$TabResponse",
            "tv.danmaku.p9138bili.p9228ui.main2.resource.MainResourceManager\$TabResponse",
        )
    }
}
