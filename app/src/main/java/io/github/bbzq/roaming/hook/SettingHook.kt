package io.github.bbzq.roaming.hook

import android.app.Activity
import io.github.bbzq.ModuleSettingsNavigator
import io.github.bbzq.roaming.BaseRoamingHook
import io.github.bbzq.roaming.RoamingEnv
import io.github.bbzq.roaming.callMethod
import io.github.bbzq.roaming.from
import io.github.bbzq.roaming.hookAfter
import io.github.bbzq.roaming.methodsNamed
import io.github.bbzq.roaming.newInstanceOrNull
import java.lang.reflect.Proxy

class SettingHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        val count =
            hookPreferenceFragment(
                "com.bilibili.p4439app.preferences.BiliPreferencesActivity\$BiliPreferencesFragment",
                "com.bilibili.app.preferences.BiliPreferencesActivity\$BiliPreferencesFragment",
            ) +
                hookPreferenceFragment(
                    "com.bilibili.p4439app.preferences.fragment.WideBiliPreferencesFragment",
                    "com.bilibili.app.preferences.fragment.WideBiliPreferencesFragment",
                )
        log("startHook: Setting, entries=$count")
    }

    private fun hookPreferenceFragment(vararg names: String): Int {
        val fragmentClass = names.firstNotNullOfOrNull { it.from(classLoader) } ?: return 0
        val method = fragmentClass.methodsNamed("onCreatePreferences")
            .firstOrNull { it.parameterCount == 2 }
            ?: return 0
        env.hookAfter(method) { param ->
            param.thisObject?.let { fragment ->
                runCatching { injectEntry(fragment) }
                    .onFailure { log("Failed to inject BBZQ settings entry", it) }
            }
        }
        return 1
    }

    private fun injectEntry(fragment: Any) {
        if (fragment.callMethod("findPreference", ENTRY_KEY) != null) return
        val activity = fragment.callMethod("getActivity") as? Activity ?: return
        val group = TARGET_GROUP_KEYS.firstNotNullOfOrNull { key ->
            fragment.callMethod("findPreference", key)
        } ?: fragment.callMethod("getPreferenceScreen") ?: return

        val entry = createPreference(fragment, activity) ?: return
        group.callMethod("addPreference", entry)
        log("Injected BBZQ settings entry into ${fragment.javaClass.name}")
    }

    private fun createPreference(fragment: Any, activity: Activity): Any? {
        val preferenceClass = PREFERENCE_CLASSES.firstNotNullOfOrNull { it.from(classLoader) }
            ?: return null
        val preference = preferenceClass.newInstanceOrNull(activity) ?: return null
        preference.callMethod("setKey", ENTRY_KEY)
        preference.callMethod("setTitle", ENTRY_TITLE)
        preference.callMethod("setSummary", ENTRY_SUMMARY)
        preference.callMethod("setPersistent", false)
        preference.callMethod("setSelectable", true)
        resolveAnchorOrder(fragment)?.let { preference.callMethod("setOrder", it + 1) }

        val setter = preference.javaClass.methodsNamed("setOnPreferenceClickListener")
            .firstOrNull { it.parameterCount == 1 && it.parameterTypes[0].isInterface }
            ?: return preference
        val listenerType = setter.parameterTypes[0]
        val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { _, method, _ ->
            if (method.name == "onPreferenceClick") {
                ModuleSettingsNavigator.open(activity)
                true
            } else {
                null
            }
        }
        setter.invoke(preference, listener)
        return preference
    }

    private fun resolveAnchorOrder(fragment: Any): Int? {
        return ANCHOR_KEYS.firstNotNullOfOrNull { key ->
            val preference = fragment.callMethod("findPreference", key) ?: return@firstNotNullOfOrNull null
            preference.callMethod("getOrder") as? Int
        }
    }

    private companion object {
        private const val ENTRY_KEY = "bbzq_settings"
        private const val ENTRY_TITLE = "高级设置"
        private const val ENTRY_SUMMARY = "BBZQ 设置"

        private val TARGET_GROUP_KEYS = arrayOf(
            "pref_key_tools_setting",
            "categoryAdvanced",
        )
        private val ANCHOR_KEYS = arrayOf(
            "pref_key_side_center",
            "pref_clear_storage",
        )
        private val PREFERENCE_CLASSES = arrayOf(
            "com.bilibili.p4439app.preferences.settingWide.CornerPreference",
            "com.bilibili.app.preferences.settingWide.CornerPreference",
            "tv.danmaku.p9138bili.widget.preference.BLPreference",
            "androidx.preference.Preference",
        )
    }
}
