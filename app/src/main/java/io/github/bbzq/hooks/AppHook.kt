package io.github.bbzq.hooks

interface AppHook {
    val targetPackageName: String

    fun install(context: HookContext)
}
