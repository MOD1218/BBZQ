package io.github.bbzq

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.bbzq.feats.RoamingRuntime
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object ModuleRemotePreferences : XposedServiceHelper.OnServiceListener {
    private const val TAG = "BBZQ"

    private val registered = AtomicBoolean(false)
    @Volatile private var appContext: Context? = null
    @Volatile private var service: XposedService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        appContext = context.applicationContext ?: context
        if (registered.compareAndSet(false, true)) {
            XposedServiceHelper.registerListener(this)
        }
    }

    fun attach(context: Context, prefs: SharedPreferences) {
        init(context)
        syncSnapshot(prefs)
    }

    override fun onServiceBind(service: XposedService) {
        this.service = service
        appContext?.let { context ->
            syncSnapshot(context.moduleSettingsPreferences())
        }
    }

    override fun onServiceDied(service: XposedService) {
        if (this.service === service) this.service = null
    }

    fun syncSnapshot(prefs: SharedPreferences) {
        val values = prefs.all
        withRemoteEditor { editor ->
            editor.clear()
            values.forEach { (key, value) -> editor.putValue(key, value) }
        }
    }

    fun applyOperations(operations: List<PreferenceOperation>) {
        if (operations.isEmpty()) return
        withRemoteEditor { editor ->
            operations.forEach { operation ->
                when (operation) {
                    PreferenceOperation.Clear -> editor.clear()
                    is PreferenceOperation.Remove -> editor.remove(operation.key)
                    is PreferenceOperation.Put -> editor.putValue(operation.key, operation.value)
                }
            }
        }
    }

    fun requestSymbolCacheRefresh(callback: (SymbolCacheRefreshResult) -> Unit) {
        requestSymbolCacheRefresh(callback, serviceWaitAttempt = 0)
    }

    private fun requestSymbolCacheRefresh(
        callback: (SymbolCacheRefreshResult) -> Unit,
        serviceWaitAttempt: Int,
    ) {
        init(appContext ?: run {
            fail(callback, "Xposed 服务尚未连接")
            return
        })
        val currentService = service ?: run {
            if (serviceWaitAttempt < SERVICE_WAIT_MAX_ATTEMPTS) {
                mainHandler.postDelayed(
                    { requestSymbolCacheRefresh(callback, serviceWaitAttempt + 1) },
                    SERVICE_WAIT_RETRY_MS,
                )
            } else {
                fail(callback, "Xposed 服务尚未连接")
            }
            return
        }
        runCatching {
            if (currentService.apiVersion < XposedService.API_102) {
                fail(callback, "当前框架不支持 API102 热重载")
                return
            }
            val runningTargets = currentService.runningTargets
            Log.i(TAG, "symbol cache refresh runningTargets=${runningTargets.joinToString { "${it.processName}:${it.state}" }}")
            val targets = runningTargets
                .filter {
                    it.state != HookedTarget.State.RELOADING &&
                        it.processName.isSymbolResolverBiliProcess()
                }
                .sortedWith(
                    compareBy<HookedTarget> { !it.processName.isSymbolResolverBiliProcess() }
                        .thenBy { it.processName.processPriority() }
                        .thenBy { it.processName },
                )
            if (targets.isEmpty()) {
                fail(callback, "未找到正在运行的 B 站进程")
                return
            }
            if (!targets.first().processName.isSymbolResolverBiliProcess()) {
                fail(callback, "未找到可执行符号重扫的 B 站主进程或下载进程")
                return
            }
            val targetProcessNames = targets
                .map { it.processName }
                .distinct()
            Log.i(TAG, "symbol cache refresh targets=${targetProcessNames.joinToString()}")
            hotReloadTargets(
                service = currentService,
                targetProcessNames = targetProcessNames,
                requestId = UUID.randomUUID().toString(),
                index = 0,
                results = mutableListOf(),
                callback = callback,
            )
        }.onFailure {
            fail(callback, "请求热重载失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun fail(
        callback: (SymbolCacheRefreshResult) -> Unit,
        message: String,
    ) {
        Log.w(TAG, message)
        dispatch(callback, SymbolCacheRefreshResult.failed(message))
    }

    private fun hotReloadTargets(
        service: XposedService,
        targetProcessNames: List<String>,
        requestId: String,
        index: Int,
        results: MutableList<SymbolCacheRefreshTargetResult>,
        callback: (SymbolCacheRefreshResult) -> Unit,
    ) {
        if (index >= targetProcessNames.size) {
            dispatch(callback, SymbolCacheRefreshResult(true, results.successMessage(), results))
            return
        }

        val processName = targetProcessNames[index]
        val forceSymbolRescan = index == 0
        val target = runCatching {
            findCurrentTarget(service, processName)
        }.getOrElse {
            val message = "查询热重载目标失败：${it.javaClass.simpleName}: ${it.message}"
            results += SymbolCacheRefreshTargetResult(processName, "ERROR", message)
            dispatch(
                callback,
                SymbolCacheRefreshResult(
                    success = false,
                    message = message,
                    targetResults = results,
                ),
            )
            return
        }
        if (target == null) {
            val message = if (forceSymbolRescan) {
                "符号重扫进程已退出，无法更新缓存"
            } else {
                "进程已退出，后续启动将读取新缓存"
            }
            results += SymbolCacheRefreshTargetResult(
                processName = processName,
                status = if (forceSymbolRescan) "MISSING" else "SKIPPED",
                message = message,
            )
            if (forceSymbolRescan) {
                dispatch(
                    callback,
                    SymbolCacheRefreshResult(
                        success = false,
                        message = message,
                        targetResults = results,
                    ),
                )
            } else {
                hotReloadTargets(service, targetProcessNames, requestId, index + 1, results, callback)
            }
            return
        }

        val extras = Bundle().apply {
            putBoolean(SymbolCacheRefreshRequest.EXTRA_FORCE_SYMBOL_RESCAN, forceSymbolRescan)
            putString(SymbolCacheRefreshRequest.EXTRA_REQUEST_ID, requestId)
        }
        val completed = AtomicBoolean(false)
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                results += SymbolCacheRefreshTargetResult(target.processName, "TIMEOUT", "等待 API102 热重载回调超时")
                dispatch(
                    callback,
                    SymbolCacheRefreshResult(
                        success = false,
                        message = "热重载 ${target.processName} 超时",
                        targetResults = results,
                    ),
                )
            }
        }
        runCatching {
            val timeoutMs = if (forceSymbolRescan) HOT_RELOAD_FORCE_TIMEOUT_MS else HOT_RELOAD_TIMEOUT_MS
            Log.i(
                TAG,
                "hot reload target=${target.processName}, state=${target.state}, forceSymbolRescan=$forceSymbolRescan",
            )
            mainHandler.postDelayed(timeout, timeoutMs)
            service.hotReloadModule(target, extras) { _, result ->
                mainHandler.post {
                    if (!completed.compareAndSet(false, true)) return@post
                    mainHandler.removeCallbacks(timeout)
                    val targetResult = SymbolCacheRefreshTargetResult(
                        processName = target.processName,
                        status = result.status().name,
                        message = result.message(),
                    )
                    results += targetResult
                    if (
                        result.status() == HotReloadResult.Status.SUCCEEDED ||
                        result.status() == HotReloadResult.Status.PROCESS_DIED && !forceSymbolRescan
                    ) {
                        hotReloadTargets(service, targetProcessNames, requestId, index + 1, results, callback)
                    } else {
                        dispatch(
                            callback,
                            SymbolCacheRefreshResult(
                                success = false,
                                message = "热重载 ${target.processName} 失败：${targetResult.detail}",
                                targetResults = results,
                            ),
                        )
                    }
                }
            }
        }.onFailure {
            if (!completed.compareAndSet(false, true)) return
            mainHandler.removeCallbacks(timeout)
            results += SymbolCacheRefreshTargetResult(target.processName, "ERROR", it.message)
            dispatch(
                callback,
                SymbolCacheRefreshResult(
                    success = false,
                    message = "热重载 ${target.processName} 失败：${it.javaClass.simpleName}: ${it.message}",
                    targetResults = results,
                ),
            )
        }
    }

    private fun findCurrentTarget(
        service: XposedService,
        processName: String,
    ): HookedTarget? =
        service.runningTargets.firstOrNull {
            it.processName == processName &&
                it.state != HookedTarget.State.RELOADING &&
                it.processName.isSymbolResolverBiliProcess()
        }

    private fun dispatch(
        callback: (SymbolCacheRefreshResult) -> Unit,
        result: SymbolCacheRefreshResult,
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            callback(result)
        } else {
            mainHandler.post { callback(result) }
        }
    }

    private fun withRemoteEditor(block: (SharedPreferences.Editor) -> Unit) {
        val currentService = service ?: return
        runCatching {
            val editor = currentService.getRemotePreferences(ModuleSettings.PREFS_NAME).edit()
            block(editor)
            editor.commit()
        }.onFailure {
            Log.w(TAG, "sync remote preferences failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun SharedPreferences.Editor.putValue(key: String, value: Any?) {
        when (value) {
            null -> remove(key)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is String -> putString(key, value)
            is Set<*> -> putStringSet(key, value.map { it.toString() }.toSet())
            is List<*> -> putStringSet(key, value.map { it.toString() }.toSet())
            else -> putString(key, value.toString())
        }
    }

    private fun Context.moduleSettingsPreferences(): SharedPreferences =
        getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)

    private fun String.isSymbolResolverBiliProcess(): Boolean {
        val packageName = biliPackageName() ?: return false
        return RoamingRuntime.isSymbolResolverProcess(packageName, this)
    }

    private fun String.processPriority(): Int {
        val packageName = biliPackageName() ?: return Int.MAX_VALUE
        return when {
            this == packageName -> 0
            endsWith(":download") -> 1
            endsWith(":web") -> 2
            else -> 3
        }
    }

    private fun String.biliPackageName(): String? =
        BILI_PACKAGES.firstOrNull { this == it || startsWith("$it:") }

    private fun List<SymbolCacheRefreshTargetResult>.successMessage(): String {
        val reloaded = count { it.status == HotReloadResult.Status.SUCCEEDED.name }
        val skipped = count {
            it.status == "SKIPPED" || it.status == HotReloadResult.Status.PROCESS_DIED.name
        }
        return if (skipped > 0) {
            "已完成缓存重扫，已重载 ${reloaded} 个进程，跳过 ${skipped} 个已退出进程"
        } else {
            "已完成缓存重扫，已重载 ${reloaded} 个进程"
        }
    }

    private val BILI_PACKAGES = setOf(
        "tv.danmaku.bili",
        "com.bilibili.app.in",
        "tv.danmaku.bilibilihd",
        "com.bilibili.app.blue",
    )

    private const val HOT_RELOAD_TIMEOUT_MS = 30_000L
    private const val HOT_RELOAD_FORCE_TIMEOUT_MS = 180_000L
    private const val SERVICE_WAIT_RETRY_MS = 500L
    private const val SERVICE_WAIT_MAX_ATTEMPTS = 10
}

sealed interface PreferenceOperation {
    data object Clear : PreferenceOperation
    data class Remove(val key: String) : PreferenceOperation
    data class Put(val key: String, val value: Any?) : PreferenceOperation
}

data class SymbolCacheRefreshResult(
    val success: Boolean,
    val message: String,
    val targetResults: List<SymbolCacheRefreshTargetResult> = emptyList(),
) {
    companion object {
        fun failed(message: String): SymbolCacheRefreshResult =
            SymbolCacheRefreshResult(false, message)
    }
}

data class SymbolCacheRefreshTargetResult(
    val processName: String,
    val status: String,
    val message: String?,
) {
    val detail: String
        get() = if (message.isNullOrBlank()) status else "$status: $message"
}
