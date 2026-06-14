package io.github.bbzq

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.bbzq.roaming.RoamingRuntime
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.util.concurrent.atomic.AtomicBoolean

class BbzqModule : XposedModule() {
    private val started = AtomicBoolean(false)
    private var processName: String = ""

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.getProcessName()
        log(
            Log.INFO,
            LOG_TAG,
            "Loaded in $processName on $frameworkName($frameworkVersionCode), api=$apiVersion",
        )
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        val packageName = param.getPackageName()
        if (packageName !in TARGET_PACKAGES || !param.isFirstPackage()) return

        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        hook(attach)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val result = chain.proceed()
                val application = chain.getThisObject() as? Application
                val baseContext = chain.getArg(0) as? Context
                val context = application ?: baseContext
                if (context != null && started.compareAndSet(false, true)) {
                    RoamingRuntime.start(
                        xposed = this,
                        packageName = packageName,
                        processName = processName,
                        application = context,
                        classLoader = param.getDefaultClassLoader(),
                    ) { message, throwable ->
                        if (throwable == null) {
                            log(Log.INFO, LOG_TAG, message)
                        } else {
                            log(Log.WARN, LOG_TAG, message, throwable)
                        }
                    }
                }
                result
            }
    }

    private companion object {
        private const val LOG_TAG = "BBZQ"

        private val TARGET_PACKAGES = setOf(
            "tv.danmaku.bili",
            "com.bilibili.app.in",
            "tv.danmaku.bilibilihd",
            "com.bilibili.app.blue",
        )
    }
}
