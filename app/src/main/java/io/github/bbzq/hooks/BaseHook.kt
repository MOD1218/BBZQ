package io.github.bbzq.hooks

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

abstract class BaseHook(
    final override val targetPackageName: String,
) : AppHook {
    protected lateinit var context: HookContext
        private set

    protected val xposed: XposedInterface
        get() = context.xposed

    protected val packageReady: PackageReadyParam
        get() = context.packageReady

    protected val classLoader: ClassLoader
        get() = context.classLoader

    protected val packageName: String
        get() = context.packageName

    protected val prefs: SharedPreferences
        get() = context.prefs

    final override fun install(context: HookContext) {
        this.context = context
        startHook()
    }

    protected fun log(message: String, throwable: Throwable? = null) {
        context.log(message, throwable)
    }

    abstract fun startHook()

    open fun lateInitHook() = Unit
}
