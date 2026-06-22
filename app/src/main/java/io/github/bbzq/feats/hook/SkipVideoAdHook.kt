package io.github.bbzq.feats.hook

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import io.github.bbzq.ModuleSettings
import io.github.bbzq.SkipVideoAdMode
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.BilibiliSponsorBlock
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.callMethod
import io.github.bbzq.feats.from
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class SkipVideoAdHook(env: RoamingEnv) : BaseRoamingHook(env) {
    @Volatile private var lastSeekTime = 0L
    @Volatile private var duration = -1L
    @Volatile private var segments: List<BilibiliSponsorBlock.Segment> = emptyList()
    @Volatile private var segmentsKey = ""
    @Volatile private var loadingSegments = false
    @Volatile private var bvid = ""
    @Volatile private var cid = ""
    private val manualNotifiedSegments = ConcurrentHashMap.newKeySet<String>()

    private var waitTime = CHECK_INTERVAL_MS
    private var playerCoreServiceRef: WeakReference<Any>? = null
    private var cardPlayerContextRef: WeakReference<Any>? = null
    private val reflectionFailureLogs = ConcurrentHashMap.newKeySet<String>()
    private val playerCoreService: Any?
        get() = playerCoreServiceRef?.get()
    private val cardPlayerContext: Any?
        get() = cardPlayerContextRef?.get()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun startHook() {
        ModuleSettings.refreshSkipVideoAdCache(prefs)
        if (!isEnabled()) return
        ensureActivityTracking()
        val count = installHookGroup("playView") { hookPlayViewUnite() } +
            installHookGroup("playerCore") { hookPlayerCoreService() } +
            installHookGroup("cardPlayer") { hookCardPlayerContext() }
        log("startHook: SkipVideoAd, methods=$count")
        if (count == 0 && env.processName == env.packageName) {
            toast("跳过视频广告未找到播放器接口")
        }
    }

    private fun installHookGroup(label: String, block: () -> Int): Int =
        runCatching(block).getOrElse {
            log("SkipVideoAd $label hook group failed", it)
            0
        }

    private fun ensureActivityTracking() {
        val application = env.hostContext as? Application ?: return
        if (!callbacksRegistered.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) {
                    topActivity = WeakReference(activity)
                }

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    if (topActivity?.get() === activity) {
                        topActivity = null
                    }
                }
            },
        )
    }

    private fun hookPlayViewUnite(): Int {
        val types = linkedSetOf<Class<*>>()
        PLAYER_MOSS_CANDIDATES.mapNotNullTo(types) { it.from(classLoader) }

        var count = 0
        types.forEach { type ->
            type.safeAllMethods("play view hook")
                .filter { method ->
                    method.name in PLAY_VIEW_METHOD_NAMES &&
                        method.parameterCount >= 1 &&
                        method.parameterTypes.firstOrNull()?.isPlayViewRequestType() == true
                }
                .distinctBy(Method::toGenericString)
                .forEach { method ->
                    count += runCatching {
                        env.hookBefore(method) { param ->
                            runCatching {
                                updateVideoIdentityFromRequest(param.args.firstOrNull())
                                if (method.name != "playViewUnite") return@runCatching
                                val handler = param.args.getOrNull(1) ?: return@runCatching
                                val wrapped = wrapResponseHandlerIfNeeded(handler)
                                if (wrapped !== handler) {
                                    param.args[1] = wrapped
                                }
                            }.onFailure {
                                log("SkipVideoAd play view hook failed at ${method.declaringClass.name}.${method.name}", it)
                            }
                        }
                        1
                    }.getOrElse {
                        log("SkipVideoAd failed to hook ${method.declaringClass.name}.${method.name}", it)
                        0
                    }
                }
        }
        return count
    }

    private fun Class<*>.isPlayViewRequestType(): Boolean {
        val methods = safeAllMethods("play view request")
        return methods.any { it.name == "getBvid" && it.parameterCount == 0 } &&
            methods.any { it.name == "getVod" && it.parameterCount == 0 }
    }

    private fun wrapResponseHandlerIfNeeded(handler: Any): Any {
        val handlerClass = handler.javaClass.interfaces.firstOrNull { type ->
            type.safeAllMethods("play view response").any { it.name == "onNext" && it.parameterCount == 1 }
        } ?: return handler
        return Proxy.newProxyInstance(
            handler.javaClass.classLoader ?: classLoader,
            collectProxyInterfaces(handler, handlerClass),
        ) { _, method, args ->
            runCatching {
                if (method.name == "onNext") {
                    updateVideoIdentityFromReply(args?.firstOrNull())
                }
            }.onFailure {
                log("SkipVideoAd response proxy failed at ${method.declaringClass.name}.${method.name}", it)
            }

            if (args == null) {
                method.invoke(handler)
            } else {
                method.invoke(handler, *args)
            }
        }
    }

    private fun updateVideoIdentityFromRequest(req: Any?) {
        if (!isEnabled() || req == null) return
        var nextBvid = req.callMethod("getBvid") as? String ?: ""
        val vod = req.callMethod("getVod") ?: return
        if (nextBvid.isEmpty()) {
            val aid = vod.callMethod("getAid").asLong() ?: return
            if (aid == -1L) return
            nextBvid = SkipVideoAdState.bvidFromAid(aid)
        }
        val nextCid = vod.callMethod("getCid").asLong()?.toString() ?: return
        updateVideoIdentity(nextBvid, nextCid)
    }

    private fun updateVideoIdentityFromReply(reply: Any?) {
        if (!isEnabled()) return
        val playArc = reply?.callMethod("getPlayArc") ?: return
        val aid = playArc.callMethod("getAid").asLong() ?: return
        if (aid == -1L) return
        val nextCid = playArc.callMethod("getCid").asLong()?.toString() ?: return
        updateVideoIdentity(SkipVideoAdState.bvidFromAid(aid), nextCid)
    }

    private fun updateVideoIdentity(nextBvid: String, nextCid: String) {
        val identity = SkipVideoAdState.resolveVideoIdentity(nextBvid, nextCid) ?: return
        if (identity.bvid == bvid && identity.cid == cid) return

        bvid = identity.bvid
        cid = identity.cid
        duration = -1L
        segments = emptyList()
        SkipVideoAdState.activateVideo(identity)
        playerCoreService?.let { SkipVideoAdState.bindController(it, identity.key) }
        cardPlayerContext?.let { SkipVideoAdState.bindController(it, identity.key) }
        segmentsKey = ""
        loadingSegments = false
        manualNotifiedSegments.clear()
        waitTime = CHECK_INTERVAL_MS
        fetchSegmentsIfNeeded()
    }

    private fun hookPlayerCoreService(): Int {
        var count = 0
        findPlayerCoreServiceClasses().forEach { type ->
            count += hookCurrentPosition(type, STATE_METHOD_NAMES)
            count += hookPlayerState(type, STATE_METHOD_NAMES)
        }
        return count
    }

    private fun hookCardPlayerContext(): Int {
        var count = 0
        findCardPlayerContextClasses().forEach { type ->
            count += hookCurrentPosition(type, CARD_STATE_METHOD_NAMES)
            count += hookPlayerState(type, CARD_STATE_METHOD_NAMES)
        }
        return count
    }

    private fun hookCurrentPosition(type: Class<*>, stateMethodNames: Set<String>): Int {
        val methods = type.safeAllMethods("current position hook")
            .filter {
                it.name == "getCurrentPosition" &&
                    it.parameterCount == 0 &&
                    it.returnType.isNumericType()
            }
            .distinctBy(Method::toGenericString)
            .toList()

        var count = 0
        methods.forEach { method ->
            runCatching {
                env.hookAfter(method) { param ->
                    runCatching {
                        if (!isEnabled()) return@runCatching
                        val controller = param.thisObject ?: return@runCatching
                        rememberPlayerController(controller, stateMethodNames)
                        if (duration <= 0) {
                            duration = resolveDuration(controller)
                            if (duration > 0) {
                                SkipVideoAdState.updateDuration(videoKey(), duration)
                            }
                        }
                        fetchSegmentsIfNeeded()
                        val position = param.result.asLong() ?: return@runCatching
                        val state = resolveState(controller, stateMethodNames)
                        if (state in RESET_PLAYER_STATES) {
                            resetPlaybackState(fetchImmediately = false)
                            return@runCatching
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastSeekTime > waitTime) {
                            lastSeekTime = now
                            waitTime = if (seekTo(position)) SKIP_COOLDOWN_MS else CHECK_INTERVAL_MS
                        }
                    }.onFailure {
                        log("SkipVideoAd currentPosition callback failed at ${method.declaringClass.name}.${method.name}", it)
                    }
                }
                count++
            }.onFailure {
                log("SkipVideoAd failed to hook ${method.declaringClass.name}.${method.name}", it)
            }
        }
        return count
    }

    private fun hookPlayerState(type: Class<*>, stateMethodNames: Set<String>): Int {
        val methods = type.safeAllMethods("player state hook")
            .filter {
                it.name in stateMethodNames &&
                    it.parameterCount == 0 &&
                    it.returnType.isNumericType()
            }
            .distinctBy(Method::toGenericString)
            .toList()

        var count = 0
        methods.forEach { method ->
            runCatching {
                env.hookAfter(method) { param ->
                    runCatching {
                        if (!isEnabled()) return@runCatching
                        val controller = param.thisObject ?: return@runCatching
                        rememberPlayerController(controller, stateMethodNames)
                        val state = param.result.asInt() ?: return@runCatching
                        if (state in 3..5 && duration <= 0) {
                            duration = resolveDuration(controller)
                            if (duration > 0) {
                                SkipVideoAdState.updateDuration(videoKey(), duration)
                            }
                        }
                        if (state in RESET_PLAYER_STATES) {
                            resetPlaybackState(fetchImmediately = true)
                        }
                    }.onFailure {
                        log("SkipVideoAd playerState callback failed at ${method.declaringClass.name}.${method.name}", it)
                    }
                }
                count++
            }.onFailure {
                log("SkipVideoAd failed to hook ${method.declaringClass.name}.${method.name}", it)
            }
        }
        return count
    }

    private fun resolveDuration(controller: Any): Long {
        return controller.callMethod("getDuration").asLong()
            ?: controller.callMethod("getRealDuration").asLong()
            ?: -1L
    }

    private fun rememberPlayerController(controller: Any, stateMethodNames: Set<String>) {
        if ("getPlayerState" in stateMethodNames) {
            cardPlayerContextRef = WeakReference(controller)
        } else {
            playerCoreServiceRef = WeakReference(controller)
        }
        SkipVideoAdState.bindController(controller, videoKey())
    }

    private fun resolveState(controller: Any, methodNames: Set<String>): Int? {
        methodNames.forEach { name ->
            controller.callMethod(name).asInt()?.let { return it }
        }
        return null
    }

    private fun resetPlaybackState(fetchImmediately: Boolean) {
        duration = -1L
        segments = emptyList()
        segmentsKey = ""
        manualNotifiedSegments.clear()
        playerCoreServiceRef = null
        cardPlayerContextRef = null
        if (fetchImmediately) {
            fetchSegmentsIfNeeded()
        }
    }

    private fun findPlayerCoreServiceClasses(): List<Class<*>> {
        val candidates = linkedSetOf<Class<*>>()
        PLAYER_CORE_SERVICE_INTERFACE.from(classLoader)?.let(candidates::add)
        PLAYER_CORE_SERVICE_CANDIDATES.mapNotNullTo(candidates) { it.from(classLoader) }
        return candidates.distinctBy { it.name }
    }

    private fun findCardPlayerContextClasses(): List<Class<*>> {
        val candidates = linkedSetOf<Class<*>>()
        CARD_PLAYER_CONTEXT_INTERFACE.from(classLoader)?.let(candidates::add)
        CARD_PLAYER_CONTEXT_CANDIDATES.mapNotNullTo(candidates) { it.from(classLoader) }
        return candidates.distinctBy { it.name }
    }

    private fun fetchSegmentsIfNeeded() {
        val config = ModuleSettings.refreshSkipVideoAdCache(prefs)
        if (!config.enabled) return
        val currentBvid = bvid
        val currentCid = cid
        if (currentBvid.isBlank() || currentCid.isBlank()) return

        val identity = SkipVideoAdState.resolveVideoIdentity(currentBvid, currentCid) ?: return
        val stateKey = identity.key
        val requestKey = "${identity.bvid}/${identity.cid}"
        if (loadingSegments || segmentsKey == requestKey) return

        loadingSegments = true
        segmentsKey = requestKey
        Thread {
            val enabledCategories = config.enabledCategories
            var result = BilibiliSponsorBlock.FetchResult(
                status = BilibiliSponsorBlock.FetchStatus.FAILED,
                segments = emptyList(),
            )
            for (attempt in 0 until 3) {
                result = BilibiliSponsorBlock(currentBvid, currentCid, enabledCategories).getSegments()
                if (result.status != BilibiliSponsorBlock.FetchStatus.FAILED) break
                if (attempt < 2) Thread.sleep(1000)
            }

            if (stateKey == videoKey()) {
                segments = result.segments
                SkipVideoAdState.updateSegments(stateKey, result.segments)
                when (result.status) {
                    BilibiliSponsorBlock.FetchStatus.SUCCESS -> {
                        log("SkipVideoAd loaded ${result.segments.size} segment(s) for $requestKey")
                        if (result.segments.isNotEmpty()) {
                            toast("已加载 ${result.segments.size} 个空降片段${loadedCategorySuffix(result.segments)}")
                        }
                    }
                    BilibiliSponsorBlock.FetchStatus.EMPTY,
                    BilibiliSponsorBlock.FetchStatus.NOT_FOUND -> {
                        log("SkipVideoAd found no skippable segments for $requestKey")
                    }
                    BilibiliSponsorBlock.FetchStatus.FAILED -> {
                        toast("广告片段数据获取失败")
                    }
                }
            }
            loadingSegments = false
        }.apply {
            name = "BBZQ-SkipVideoAd"
            isDaemon = true
            start()
        }
    }

    private fun videoKey(): String =
        SkipVideoAdState.resolveVideoIdentity(bvid, cid)?.key ?: ""

    private fun seekTo(position: Long): Boolean {
        val config = ModuleSettings.getSkipVideoAdCache(prefs)
        if (!config.enabled) return false
        val videoDuration = duration
        if (videoDuration > 0 && position > videoDuration) return false

        segments.forEach { segment ->
            val mode = config.modes[segment.category] ?: SkipVideoAdMode.IGNORE
            if (mode == SkipVideoAdMode.IGNORE) return@forEach
            val start = (segment.segment[0] * 1000).toLong()
            val end = (segment.segment[1] * 1000).toLong()
            if (position >= start - PRE_SKIP_THRESHOLD_MS && position < end) {
                return when (mode) {
                    SkipVideoAdMode.AUTO_SKIP -> seekPlayerTo(end, segment)
                    SkipVideoAdMode.MANUAL_SKIP -> {
                        val key = "${segmentStateKey(segment)}:manual"
                        if (manualNotifiedSegments.add(key)) {
                            showManualSkipDialog(end, segment)
                        }
                        false
                    }
                    SkipVideoAdMode.SHOW_IN_BAR,
                    SkipVideoAdMode.IGNORE -> false
                }
            }
        }
        return false
    }

    private fun seekPlayerTo(position: Long, segment: BilibiliSponsorBlock.Segment): Boolean {
        val controllers = buildList {
            cardPlayerContext?.let(::add)
            playerCoreService?.let(::add)
        }.distinctBy { it.javaClass.name }
        if (controllers.isEmpty()) return false

        controllers.forEach { controller ->
            if (invokeSeek(controller, position)) {
                log("SkipVideoAd skipped ${segment.category} to $position via ${controller.javaClass.name}")
                toast(skipToastMessage(segment))
                return true
            }
        }
        return false
    }

    private fun invokeSeek(controller: Any, position: Long): Boolean {
        val method = controller.javaClass.safeAllMethods("seek target")
            .firstOrNull { it.isSeekToMethod() }
            ?: return false
        val args = when (method.parameterCount) {
            1 -> arrayOf(position.coerceToMethodType(method.parameterTypes[0]))
            2 -> arrayOf(position.coerceToMethodType(method.parameterTypes[0]), true)
            else -> return false
        }
        return runCatching {
            method.invoke(controller, *args)
            true
        }.onFailure {
            log("SkipVideoAd seekTo failed via ${controller.javaClass.name}", it)
        }.getOrDefault(false)
    }

    private fun Method.isSeekToMethod(): Boolean {
        if (name != "seekTo" || parameterCount !in 1..2) return false
        if (!parameterTypes[0].isNumericType()) return false
        return parameterCount == 1 || parameterTypes[1].isBooleanType()
    }

    private fun Class<*>.isNumericType(): Boolean =
        this == Int::class.javaPrimitiveType ||
            this == Int::class.javaObjectType ||
            this == Long::class.javaPrimitiveType ||
            this == Long::class.javaObjectType

    private fun Class<*>.isBooleanType(): Boolean =
        this == Boolean::class.javaPrimitiveType || this == Boolean::class.javaObjectType

    private fun Class<*>.safeAllMethods(reason: String): List<Method> =
        runCatching { allMethods().toList() }
            .getOrElse {
                logReflectionFailure(reason, name, it)
                emptyList()
            }

    private fun logReflectionFailure(reason: String, typeName: String, throwable: Throwable) {
        if (reflectionFailureLogs.add("$reason#$typeName")) {
            log("SkipVideoAd failed to inspect $typeName for $reason", throwable)
        }
    }

    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(env.hostContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showManualSkipDialog(position: Long, segment: BilibiliSponsorBlock.Segment) {
        val activity = topActivity?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            toast(manualSkipToastMessage(segment))
            return
        }

        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread

            val themeId = activity.resources.getIdentifier("AppTheme.Dialog.Alert", "style", activity.packageName)
            val builder = if (themeId != 0) AlertDialog.Builder(activity, themeId) else AlertDialog.Builder(activity)

            val message = buildString {
                append("检测到")
                append(categoryLabel(segment))
                append("片段，是否跳过？\n")
                append(formatSeconds(segment.segment[0]))
                append(" - ")
                append(formatSeconds(segment.segment[1]))
            }

            val dialog = builder
                .setTitle("空降助手")
                .setMessage(message)
                .setPositiveButton("跳过") { _, _ ->
                    seekPlayerTo(position, segment)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

            dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
        }
    }

    private fun isEnabled(): Boolean =
        ModuleSettings.isSkipVideoAdEnabledCached(prefs)

    private fun Any?.asInt(): Int? = when (this) {
        is Number -> toInt()
        is String -> toIntOrNull()
        else -> null
    }

    private fun Any?.asLong(): Long? = when (this) {
        is Number -> toLong()
        is String -> toLongOrNull()
        else -> null
    }

    private fun Long.coerceToMethodType(type: Class<*>): Any =
        when (type) {
            Int::class.javaPrimitiveType,
            Int::class.javaObjectType,
            Short::class.javaPrimitiveType,
            Short::class.javaObjectType -> toInt()
            else -> this
        }

    private fun collectProxyInterfaces(original: Any, primaryType: Class<*>): Array<Class<*>> =
        buildSet {
            add(primaryType)
            original.javaClass.interfaces.forEach(::add)
            original.javaClass.takeIf { it.isInterface }?.let(::add)
        }.toTypedArray()

    private fun loadedCategorySuffix(segments: List<BilibiliSponsorBlock.Segment>): String {
        val labels = segments.map(::categoryLabel).distinct()
        if (labels.isEmpty()) return ""
        val preview = labels.take(3).joinToString("、")
        return if (labels.size > 3) "（$preview 等）" else "（$preview）"
    }

    private fun skipToastMessage(segment: BilibiliSponsorBlock.Segment): String =
        "已跳过${categoryLabel(segment)}"

    private fun manualSkipToastMessage(segment: BilibiliSponsorBlock.Segment): String =
        "即将跳过${categoryLabel(segment)}"

    private fun categoryLabel(segment: BilibiliSponsorBlock.Segment): String =
        ModuleSettings.skipVideoAdCategories
            .firstOrNull { it.key == segment.category }
            ?.label
            ?: segment.category

    private fun segmentStateKey(segment: BilibiliSponsorBlock.Segment): String {
        val uuid = segment.uuid.trim()
        if (uuid.isNotEmpty()) return uuid
        return buildString {
            append(segment.category)
            append(':')
            append(segment.segment[0])
            append(':')
            append(segment.segment[1])
        }
    }

    private fun formatSeconds(value: Float): String = String.format(Locale.US, "%.1fs", value)

    private companion object {
        private const val PLAYER_CORE_SERVICE_INTERFACE = "tv.danmaku.biliplayerv2.service.IPlayerCoreService"
        private const val CARD_PLAYER_CONTEXT_INTERFACE = "tv.danmaku.video.bilicardplayer.ICardPlayerContext"
        private const val CHECK_INTERVAL_MS = 250L
        private const val PRE_SKIP_THRESHOLD_MS = 300L
        private const val SKIP_COOLDOWN_MS = 1000L
        private val PLAY_VIEW_METHOD_NAMES = setOf("executePlayViewUnite", "playViewUnite")
        private val STATE_METHOD_NAMES = setOf("getState")
        private val CARD_STATE_METHOD_NAMES = setOf("getPlayerState", "getState")
        private val RESET_PLAYER_STATES = setOf(2)

        private val PLAYER_CORE_SERVICE_CANDIDATES = arrayOf(
            "tv.danmaku.biliplayerv2.service.PlayerCoreService",
            "tv.danmaku.biliplayerimpl.core.PlayerCoreService",
            "com.bilibili.playerbizcommon.service.PlayerCoreService",
        )
        private val CARD_PLAYER_CONTEXT_CANDIDATES = arrayOf(
            "tv.danmaku.video.bilicardplayer.CardPlayerContext",
        )
        private val PLAYER_MOSS_CANDIDATES = arrayOf(
            "com.bapis.bilibili.app.playerunite.v1.PlayerMoss",
            "com.bapis.bilibili.p4218app.playerunite.p4240v1.PlayerMoss",
            "com.bapis.bilibili.p4218app.playerunite.p4240v1.KPlayerMoss",
        )

        private val callbacksRegistered = java.util.concurrent.atomic.AtomicBoolean(false)

        @Volatile
        private var topActivity: WeakReference<Activity>? = null
    }
}
