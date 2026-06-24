package io.github.bbzq.feats.hook

import android.view.View
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.symbol.RestoredMineProfileSymbols
import kotlin.LazyThreadSafetyMode

class MineProfileHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private val mineSymbols: RestoredMineProfileSymbols? by lazy(LazyThreadSafetyMode.NONE) {
        env.symbols?.mineProfile?.restore(classLoader)
    }

    override fun startHook() {
        if (env.processName != env.packageName) return

        val symbols = mineSymbols ?: run {
            log("MineProfileHook skipped: symbols missing")
            return
        }

        env.hookBefore(symbols.onResume) { param ->
            runCatching {
                if (!ModuleSettings.isMineRemoveVipEnabled(prefs)) return@runCatching
                val fragment = param.thisObject ?: return@runCatching
                val vipView = symbols.resolveVipView(fragment) ?: return@runCatching
                vipView.visibility = if (ModuleSettings.isMineKeepVipSpaceEnabled(prefs)) {
                    View.INVISIBLE
                } else {
                    View.GONE
                }
            }.onFailure {
                log("MineProfile vip hook failed at ${symbols.onResume.declaringClass.name}.${symbols.onResume.name}", it)
            }
        }
        log("startHook: MineProfile, methods=1")
    }
}
