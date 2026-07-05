package io.github.bbzq.feats

import android.content.Context
import java.lang.reflect.Modifier

internal object HostAccountResolver {
    fun resolve(context: Context, classLoader: ClassLoader): Snapshot {
        return runCatching {
            val accountClass = ACCOUNT_CLASS_NAMES.firstNotNullOfOrNull(classLoader::findClassOrNull)
                ?: return@runCatching Snapshot.LOGGED_OUT
            val getMethod = accountClass.allMethods().firstOrNull { method ->
                Modifier.isStatic(method.modifiers) && method.name == "get" &&
                    method.returnType == accountClass && method.parameterCount == 1 &&
                    Context::class.java.isAssignableFrom(method.parameterTypes[0])
            } ?: return@runCatching Snapshot.LOGGED_OUT
            val account = getMethod.invoke(null, context) ?: return@runCatching Snapshot.LOGGED_OUT
            val isLogin = accountClass.allMethods().firstOrNull { method ->
                method.name == "isLogin" && method.parameterCount == 0 &&
                    method.returnType == Boolean::class.javaPrimitiveType
            } ?: return@runCatching Snapshot.LOGGED_OUT
            if (isLogin.invoke(account) != true) return@runCatching Snapshot.LOGGED_OUT

            val uid = accountClass.allMethods().firstOrNull { method ->
                method.name == "mid" && method.parameterCount == 0 &&
                    method.returnType == Long::class.javaPrimitiveType
            }?.invoke(account)?.toString().orEmpty().takeUnless { it == "0" }.orEmpty()
            Snapshot(true, uid, resolveUserName(classLoader))
        }.getOrDefault(Snapshot.LOGGED_OUT)
    }

    private fun resolveUserName(classLoader: ClassLoader): String {
        val managerClass = ACCOUNT_INFO_CLASS_NAMES.firstNotNullOfOrNull(classLoader::findClassOrNull)
            ?: return ""
        val manager = managerClass.allMethods().firstOrNull { method ->
            Modifier.isStatic(method.modifiers) && method.name == "get" && method.parameterCount == 0
        }?.invoke(null) ?: return ""
        val profile = manager.callMethod("getAccountInfoFromCache") ?: return ""
        return profile.callMethod("getUserName")?.toString().orEmpty()
    }

    data class Snapshot(val loggedIn: Boolean, val uid: String, val userName: String) {
        companion object {
            val LOGGED_OUT = Snapshot(false, "", "")
        }
    }

    private val ACCOUNT_CLASS_NAMES = arrayOf(
        "com.bilibili.lib.accounts.BiliAccounts",
        "com.bilibili.app.accounts.BiliAccounts",
        "com.bilibili.p4439app.accounts.BiliAccounts",
    )

    private val ACCOUNT_INFO_CLASS_NAMES = arrayOf(
        "com.bilibili.lib.accountinfo.BiliAccountInfo",
        "com.bilibili.app.accountinfo.BiliAccountInfo",
    )
}
