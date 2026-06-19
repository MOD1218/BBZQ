package io.github.bbzq.feats.hook

import android.view.View
import android.view.ViewGroup
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.callMethod
import io.github.bbzq.feats.from
import io.github.bbzq.feats.hookAfterAllMethods

class HomeComponentHideHook(env: io.github.bbzq.feats.RoamingEnv) : BaseRoamingHook(env) {
    private val knownComponents = linkedMapOf<String, String>()

    override fun startHook() {
        if (env.processName != env.packageName) return
        val fragmentClass = ANDROIDX_FRAGMENT.from(classLoader)
        if (fragmentClass == null) {
            log("startHook: HomeComponentHide missing androidx.fragment.app.Fragment")
            return
        }

        var count = 0
        count += env.hookAfterAllMethods(fragmentClass, "onResume") { param ->
            processFragment(param.thisObject)
        }
        count += env.hookAfterAllMethods(fragmentClass, "onViewCreated") { param ->
            processFragment(param.thisObject)
        }

        if (count == 0) {
            log("startHook: HomeComponentHide no hook point found")
        } else {
            log("startHook: HomeComponentHide methods=$count")
        }
    }

    private fun processFragment(fragment: Any?) {
        if (fragment == null) return
        val root = fragment.callMethod("getView") as? View ?: return
        if (!containsRecyclerView(root, 0)) return
        val className = fragment.javaClass.name
        if (!isCandidateComponent(fragment, className)) return

        saveKnownComponent(className)
        if (!shouldHide(className)) return

        hideRecyclerViews(root)
        attachPersistentHider(root, className)
    }

    private fun isCandidateComponent(fragment: Any, className: String): Boolean {
        if (!className.startsWith("com.bilibili") && !className.startsWith("tv.danmaku")) return false
        val classNameLower = className.lowercase()
        if (EXCLUDED_KEYWORDS.any(classNameLower::contains)) return false
        return isUnderHomeContainer(fragment)
    }

    private fun isUnderHomeContainer(fragment: Any): Boolean {
        var current = fragment.callMethod("getParentFragment")
        var guard = 0
        while (current != null && guard < 20) {
            guard += 1
            val name = current.javaClass.name.lowercase()
            if (HOME_CONTAINER_KEYWORDS.any(name::contains)) return true
            current = current.callMethod("getParentFragment")
        }
        return false
    }

    private fun shouldHide(className: String): Boolean {
        if (ModuleSettings.isHideAllHomeComponentsEnabled(prefs)) return true
        if (!ModuleSettings.isCustomHomeComponentHideEnabled(prefs)) return false
        return className in ModuleSettings.getHiddenHomeComponents(prefs)
    }

    private fun saveKnownComponent(className: String) {
        if (knownComponents.containsKey(className)) return
        val snapshot = ModuleSettings.getKnownHomeComponents(prefs)
            .mapNotNull(::decodeComponent)
            .associateByTo(linkedMapOf(), { it.className }, { it.name })
        if (className in snapshot) {
            knownComponents.putAll(snapshot)
            return
        }

        val name = className.substringAfterLast('.').ifBlank { className }
        knownComponents.clear()
        knownComponents.putAll(snapshot)
        knownComponents[className] = name

        val encoded = knownComponents.entries.mapIndexed { index, entry ->
            encodeComponent(index, entry.value, entry.key)
        }.toMutableSet()
        prefs.edit()
            .putStringSet(ModuleSettings.KEY_KNOWN_HOME_COMPONENTS, encoded)
            .apply()
    }

    private fun attachPersistentHider(root: View, className: String) {
        if (root.getTag(LISTENER_TAG_KEY) != null) return
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            if (!shouldHide(className)) return@OnGlobalLayoutListener
            hideRecyclerViews(root)
        }
        runCatching {
            root.viewTreeObserver?.addOnGlobalLayoutListener(listener)
            root.setTag(LISTENER_TAG_KEY, listener)
        }.onFailure {
            log("HomeComponentHide failed to attach listener for $className", it)
        }
    }

    private fun hideRecyclerViews(root: View) {
        val targets = ArrayList<View>()
        collectRecyclerViews(root, 0, targets)
        targets.forEach { view ->
            if (view.visibility != View.GONE) {
                view.visibility = View.GONE
            }
        }
    }

    private fun containsRecyclerView(view: View, depth: Int): Boolean {
        if (depth > MAX_TREE_DEPTH) return false
        if (view.javaClass.name.contains(RECYCLER_VIEW_KEYWORD)) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val child = view.getChildAt(index) ?: continue
                if (containsRecyclerView(child, depth + 1)) return true
            }
        }
        return false
    }

    private fun collectRecyclerViews(view: View, depth: Int, out: MutableList<View>) {
        if (depth > MAX_TREE_DEPTH) return
        if (view.javaClass.name.contains(RECYCLER_VIEW_KEYWORD)) {
            out += view
            return
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val child = view.getChildAt(index) ?: continue
                collectRecyclerViews(child, depth + 1, out)
            }
        }
    }

    private fun encodeComponent(order: Int, name: String, className: String): String =
        listOf(order.toString(), name.sanitizePart(), className.sanitizePart()).joinToString("\t")

    private fun decodeComponent(raw: String): HomeComponentItem? {
        val parts = raw.split('\t', limit = 3)
        if (parts.size != 3) return null
        val order = parts[0].toIntOrNull() ?: return null
        return HomeComponentItem(order, parts[1], parts[2])
    }

    private fun String.sanitizePart(): String =
        replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private data class HomeComponentItem(
        val order: Int,
        val name: String,
        val className: String,
    )

    private companion object {
        private const val ANDROIDX_FRAGMENT = "androidx.fragment.app.Fragment"
        private const val RECYCLER_VIEW_KEYWORD = "RecyclerView"
        private const val MAX_TREE_DEPTH = 40
        private const val LISTENER_TAG_KEY = 0x7F0B1120
        private val HOME_CONTAINER_KEYWORDS = listOf(
            "main2.homefragment",
            "homefragmentv2",
        )
        private val EXCLUDED_KEYWORDS = listOf(
            "search",
            "dynamic",
            "history",
            "favorite",
            "space",
            "reply",
            "detail",
        )
    }
}
