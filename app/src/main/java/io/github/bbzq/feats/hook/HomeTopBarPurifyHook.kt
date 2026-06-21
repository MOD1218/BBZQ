package io.github.bbzq.feats.hook

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.widget.TextView
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allFields
import io.github.bbzq.feats.from
import io.github.bbzq.feats.hookAfterMethod
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.hookBeforeMethod
import io.github.bbzq.feats.methodsNamed
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class HomeTopBarPurifyHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (env.processName != env.packageName) return
        val hideGameMenu = ModuleSettings.isHideHomeTopBarPromotionEnabled(prefs)
        val hideSearchDefaultWord = ModuleSettings.isHideHomeSearchDefaultWordEnabled(prefs)
        if (!hideGameMenu && !hideSearchDefaultWord) {
            log("startHook: HomeTopBarPurify disabled")
            return
        }

        val count =
            (if (hideGameMenu) hookGameMenuItem() else 0) +
                (if (hideSearchDefaultWord) hookSearchDefaultWord() else 0)
        if (count == 0) {
            log("startHook: HomeTopBarPurify no hook point found")
        } else {
            log("startHook: HomeTopBarPurify methods=$count")
        }
    }

    private fun hookGameMenuItem(): Int {
        val menuItemClass = MENU_ITEM_CLASS.from(classLoader) ?: return 0
        return env.hookBeforeMethod(
            menuItemClass,
            "b",
            Menu::class.java,
            MenuInflater::class.java,
        ) { param ->
            if (!ModuleSettings.isHideHomeTopBarPromotionEnabled(prefs)) return@hookBeforeMethod
            if (param.thisObject.hasGameMenuAction()) {
                param.result = null
            }
        }
    }

    private fun hookSearchDefaultWord(): Int {
        var count = 0
        val baseFragmentClass = BASE_MAIN_FRAME_FRAGMENT.from(classLoader)
        val mainFragmentClass = MAIN_FRAGMENT.from(classLoader)
        val defaultWordClass = DEFAULT_SEARCH_WORD_CLASS.from(classLoader)

        if (baseFragmentClass != null) {
            count += env.hookAfterMethod(
                baseFragmentClass,
                "onViewCreated",
                View::class.java,
                Bundle::class.java,
            ) { param ->
                if (ModuleSettings.isHideHomeSearchDefaultWordEnabled(prefs)) {
                    clearSearchText(param.thisObject)
                }
            }
        }

        if (mainFragmentClass != null && defaultWordClass != null) {
            findDefaultWordMethods(mainFragmentClass, defaultWordClass).forEach { method ->
                env.hookBefore(method) { param ->
                    if (!ModuleSettings.isHideHomeSearchDefaultWordEnabled(prefs)) return@hookBefore
                    clearSearchText(param.thisObject)
                    param.result = null
                }
                count += 1
            }
        }

        return count
    }

    private fun findDefaultWordMethods(mainFragmentClass: Class<*>, defaultWordClass: Class<*>): List<Method> =
        mainFragmentClass.methodsNamed(null)
            .filter { method ->
                method.returnType == Void.TYPE &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0] == defaultWordClass &&
                    !Modifier.isStatic(method.modifiers) &&
                    !Modifier.isAbstract(method.modifiers)
            }
            .distinctBy(Method::toGenericString)
            .toList()

    private fun clearSearchText(fragment: Any?) {
        val searchText = fragment.findSearchTextView() ?: return
        runCatching {
            searchText.clearAnimation()
            searchText.text = ""
        }.onFailure {
            log("HomeTopBarPurify failed to clear search text", it)
        }
    }

    private fun Any?.hasGameMenuAction(): Boolean {
        if (this == null) return false
        return javaClass.allFields().any { field ->
            val value = runCatching { field.get(this) }.getOrNull() ?: return@any false
            value !is String && value.hasStringField(::isGameMenuAction)
        }
    }

    private fun Any.hasStringField(predicate: (String) -> Boolean): Boolean =
        javaClass.allFields().any { field ->
            if (field.type != String::class.java) return@any false
            val value = runCatching { field.get(this) as? String }.getOrNull() ?: return@any false
            predicate(value)
        }

    private fun Any?.findSearchTextView(): TextView? {
        if (this == null) return null
        return javaClass.allFields()
            .firstNotNullOfOrNull { field ->
                if (field.type.name != SWITCH_TEXT_VIEW_CLASS) return@firstNotNullOfOrNull null
                runCatching { field.get(this) as? TextView }.getOrNull()
            }
    }

    private fun isGameMenuAction(action: String): Boolean =
        action == GAME_MENU_ACTION || action.startsWith("$GAME_MENU_ACTION?")

    private companion object {
        private const val MENU_ITEM_CLASS = "com.bilibili.lib.homepage.startdust.menu.a"
        private const val BASE_MAIN_FRAME_FRAGMENT = "tv.danmaku.bili.ui.main2.basic.BaseMainFrameFragment"
        private const val MAIN_FRAGMENT = "tv.danmaku.bili.ui.main2.MainFragment"
        private const val DEFAULT_SEARCH_WORD_CLASS = "com.bilibili.app.comm.list.common.api.b"
        private const val SWITCH_TEXT_VIEW_CLASS = "tv.danmaku.bili.widget.SwitchTextView"
        private const val GAME_MENU_ACTION = "action://game_center/home/menu"
    }
}
