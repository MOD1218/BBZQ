package io.github.bbzq.feats.hook

import io.github.bbzq.AccessKeyRepository
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.symbol.RestoredAccountSymbols
import kotlin.LazyThreadSafetyMode

class AccessKeyHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private val accountSymbols: RestoredAccountSymbols? by lazy(LazyThreadSafetyMode.NONE) {
        env.symbols?.account?.restore(classLoader)
    }

    override fun startHook() {
        if (env.processName != env.packageName) return
        if (accountSymbols == null) {
            log("AccessKeyHook skipped: symbols missing")
            return
        }

        AccessKeyRepository.register {
            runCatching { getAccessKey() }
                .onFailure { log("AccessKey read failed", it) }
                .getOrNull()
        }
        log("AccessKeyHook installed")
    }

    private fun getAccessKey(): String? {
        val symbols = accountSymbols ?: return null
        val account = runCatching {
            val args = if (symbols.getMethod.parameterCount == 0) {
                emptyArray()
            } else {
                arrayOf(env.hostContext)
            }
            symbols.getMethod.invoke(null, *args)
        }.onFailure {
            log("AccessKey invoke account factory failed: ${symbols.accountClass.name}.${symbols.getMethod.name}", it)
        }.getOrNull() ?: return null

        return runCatching {
            symbols.accessKeyMethod.invoke(account) as? String
        }.onFailure {
            log("AccessKey invoke method failed: ${account.javaClass.name}.${symbols.accessKeyMethod.name}", it)
        }.getOrNull()?.takeIf { AccessKeyRepository.looksLikeAccessKey(it) }
    }
}
