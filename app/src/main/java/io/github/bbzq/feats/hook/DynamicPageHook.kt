package io.github.bbzq.feats.hook

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.hookAfterMethod
import java.util.ArrayDeque

class DynamicPageHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (env.processName != env.packageName) return
        if (
            !ModuleSettings.isDynamicPreferredVideoTabEnabled(prefs) &&
            !ModuleSettings.isDynamicRemoveCityTabEnabled(prefs) &&
            !ModuleSettings.isDynamicRemoveSchoolTabEnabled(prefs)
        ) {
            log("startHook: DynamicPage disabled")
            return
        }

        env.hookAfterMethod(Activity::class.java, "onResume") { param ->
            val activity = param.thisObject as? Activity ?: return@hookAfterMethod
            scheduleSweep(activity)
        }

        log("startHook: DynamicPage Activity.onResume")
    }

    private fun scheduleSweep(activity: Activity) {
        val root = activity.window?.decorView ?: return
        SWEEP_DELAYS_MS.forEach { delay ->
            root.postDelayed({
                runCatching {
                    sweep(activity, root)
                }.onFailure {
                    log("DynamicPage sweep failed for ${activity.javaClass.name}", it)
                }
            }, delay)
        }
    }

    private fun sweep(activity: Activity, root: View) {
        if (!isDynamicCandidate(activity, root)) return

        var changed = false
        if (ModuleSettings.isDynamicPreferredVideoTabEnabled(prefs)) {
            changed = clickPreferredVideoTab(root) || changed
        }
        if (ModuleSettings.isDynamicRemoveCityTabEnabled(prefs)) {
            changed = hideMatchingTab(root, CITY_TAB_LABELS) || changed
        }
        if (ModuleSettings.isDynamicRemoveSchoolTabEnabled(prefs)) {
            changed = hideMatchingTab(root, SCHOOL_TAB_LABELS) || changed
        }

        if (changed) {
            log("DynamicPage updated for ${activity.javaClass.name}")
        }
    }

    private fun isDynamicCandidate(activity: Activity, root: View): Boolean {
        val className = activity.javaClass.name.lowercase()
        if (DYNAMIC_ACTIVITY_KEYWORDS.any(className::contains)) return true
        return findMatchingTextView(root, DYNAMIC_TAB_LABELS) != null
    }

    private fun clickPreferredVideoTab(root: View): Boolean {
        val videoTab = findMatchingTextView(root, VIDEO_TAB_LABELS) ?: return false
        if (videoTab.isSelected || videoTab.isActivated) return false
        return performClick(videoTab)
    }

    private fun hideMatchingTab(root: View, labels: Set<String>): Boolean {
        val matches = findAllMatchingTextViews(root, labels)
        var changed = false
        matches.forEach { view ->
            val target = resolveHideTarget(view)
            if (target.visibility != View.GONE) {
                target.visibility = View.GONE
                changed = true
            }
        }
        return changed
    }

    private fun resolveHideTarget(view: View): View {
        val parent = view.parent as? View
        if (parent == null || parent === view) return view
        if (parent.isClickable || parent.hasOnClickListeners()) return parent
        if (parent is ViewGroup && parent.childCount <= 3) return parent
        return view
    }

    private fun findMatchingTextView(root: View, labels: Set<String>): TextView? =
        findAllMatchingTextViews(root, labels).firstOrNull()

    private fun findAllMatchingTextViews(root: View, labels: Set<String>): List<TextView> {
        val out = ArrayList<TextView>()
        val queue = ArrayDeque<View>()
        queue += root
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_VIEW_SCAN_NODES) {
            val view = queue.removeFirst()
            visited += 1

            if (view is TextView && view.isShown && matchesAnyLabel(view.text, labels)) {
                out += view
            }

            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    queue += view.getChildAt(index)
                }
            }
        }

        return out
    }

    private fun matchesAnyLabel(rawText: CharSequence?, labels: Set<String>): Boolean {
        val normalized = normalizeText(rawText)
        if (normalized.isEmpty()) return false
        return labels.any { label ->
            normalized == label || normalized.startsWith(label)
        }
    }

    private fun normalizeText(rawText: CharSequence?): String =
        rawText?.toString()
            ?.replace("\u3000", " ")
            ?.replace("\n", "")
            ?.replace("\r", "")
            ?.replace("\t", "")
            ?.replace(" ", "")
            ?.trim()
            .orEmpty()

    private fun performClick(view: View): Boolean {
        if (!view.isEnabled || !view.isShown) return false
        if (view.isClickable || view.hasOnClickListeners()) return view.performClick()
        val parent = view.parent as? View ?: return false
        return parent.isEnabled && parent.isShown && (parent.isClickable || parent.hasOnClickListeners()) && parent.performClick()
    }

    private companion object {
        private val SWEEP_DELAYS_MS = longArrayOf(0L, 250L, 700L)
        private const val MAX_VIEW_SCAN_NODES = 512
        private val DYNAMIC_ACTIVITY_KEYWORDS = listOf(
            "dynamic",
            "space",
            "following",
            "feed",
            "opus",
        )
        private val DYNAMIC_TAB_LABELS = setOf("同城", "校园", "视频")
        private val VIDEO_TAB_LABELS = setOf("视频")
        private val CITY_TAB_LABELS = setOf("同城")
        private val SCHOOL_TAB_LABELS = setOf("校园")
    }
}
