package io.github.bbzq.hooks

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.bbzq.ModuleSettings
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

class AutoLikeVideoDetailHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    private val clickedViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val componentHookInstalled = AtomicBoolean(false)

    override fun startHook() {
        val componentHooks = hookLikeComponent()
        val lifecycleMethods = VIDEO_ACTIVITY_CLASSES
            .mapNotNull { HostAccess.findClass(classLoader, it) }
            .flatMap { type ->
                listOfNotNull(
                    HostAccess.method(type, listOf("onResume")) { it.parameterCount == 0 },
                    HostAccess.method(type, listOf("onWindowFocusChanged")) {
                        it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
                    },
                )
            }
            .distinctBy { it.toGenericString() }

        lifecycleMethods.forEach { method ->
            xposed.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    val focused = method.name != "onWindowFocusChanged" || chain.getArg(0) == true
                    if (focused && ModuleSettings.isAutoLikeVideoDetailEnabled(prefs)) {
                        (chain.thisObject as? Activity)?.let(::scheduleLike)
                    }
                    result
                }
        }
        log(
            "Installed auto-like hook: component=$componentHooks, " +
                "lifecycle=${lifecycleMethods.size}",
        )
    }

    private fun hookLikeComponent(): Int {
        val marker = HostMethodResolver(context).resolve(
            cacheKey = "auto_like_component_marker",
            fixedCandidates = { emptySequence() },
            usingStrings = listOf("LikeClicked(view="),
            validate = { true },
        ) ?: return 0

        var componentHost = marker.declaringClass
        while (true) {
            val outer = componentHost.declaringClass ?: break
            componentHost = outer
        }
        val componentMapField = HostAccess.fields(componentHost)
            .firstOrNull { Map::class.java.isAssignableFrom(it.type) }
            ?: return 0

        val constructors = componentHost.declaredConstructors.toList()
        constructors.forEach { constructor ->
            constructor.isAccessible = true
            xposed.hook(constructor)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!componentHookInstalled.get()) {
                        val host = chain.thisObject
                        val map = host?.let {
                            runCatching { componentMapField.get(it) as? Map<*, *> }.getOrNull()
                        }
                        val bindMethod = map?.keys
                            ?.asSequence()
                            ?.filterNotNull()
                            ?.map(Any::javaClass)
                            ?.distinct()
                            ?.firstNotNullOfOrNull(::findLikeBindMethod)
                        if (bindMethod != null && componentHookInstalled.compareAndSet(false, true)) {
                            hookLikeBind(bindMethod)
                        }
                    }
                    result
                }
        }
        return constructors.size
    }

    private fun findLikeBindMethod(type: Class<*>): Method? {
        if (HostAccess.fields(type).none { Runnable::class.java.isAssignableFrom(it.type) }) {
            return null
        }
        return HostAccess.methods(type).firstOrNull { method ->
            method.returnType == Void.TYPE &&
                method.parameterCount == 5 &&
                method.parameterTypes[0] == type &&
                LinearLayout::class.java.isAssignableFrom(method.parameterTypes[4])
        }
    }

    private fun hookLikeBind(method: Method) {
        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val result = chain.proceed()
                if (ModuleSettings.isAutoLikeVideoDetailEnabled(prefs)) {
                    (chain.getArg(4) as? View)?.let(::scheduleLike)
                }
                result
            }
    }

    private fun scheduleLike(activity: Activity) {
        scheduleLike(activity.window?.decorView ?: return)
    }

    private fun scheduleLike(root: View) {
        SCAN_DELAYS.forEach { delay ->
            root.postDelayed({
                if (!ModuleSettings.isAutoLikeVideoDetailEnabled(prefs)) return@postDelayed
                val likeView = findLikeView(root) ?: return@postDelayed
                if (likeView in clickedViews || isLiked(likeView)) return@postDelayed
                if (AutoLikeState.hasDetail() && !AutoLikeState.canClick()) return@postDelayed
                if (performClick(likeView)) {
                    clickedViews += likeView
                    if (AutoLikeState.hasDetail()) {
                        AutoLikeState.markClicked()
                    }
                }
            }, delay)
        }
    }

    private fun findLikeView(root: View): View? {
        LIKE_VIEW_IDS.forEach { name ->
            val id = root.resources.getIdentifier(name, "id", root.context.packageName)
            if (id != 0) root.findViewById<View>(id)?.let { return it }
        }
        return findView(root) { view ->
            val text = when (view) {
                is TextView -> view.text?.toString().orEmpty()
                else -> view.contentDescription?.toString().orEmpty()
            }
            text == "点赞" || text.contains("点赞视频")
        }
    }

    private fun isLiked(view: View): Boolean {
        if (view.isSelected || view.isActivated) return true
        val markers = listOf("已点赞", "取消点赞", "已赞")
        return findView(view) { candidate ->
            candidate.isSelected ||
                candidate.isActivated ||
                markers.any { marker ->
                    candidate.contentDescription?.contains(marker) == true ||
                        (candidate as? TextView)?.text?.contains(marker) == true
                }
        } != null
    }

    private fun performClick(view: View): Boolean {
        if (!view.isShown || !view.isEnabled) return false
        if (view.isClickable || view.hasOnClickListeners()) return view.callOnClick()
        val parent = view.parent as? View ?: return false
        return parent.isShown &&
            parent.isEnabled &&
            (parent.isClickable || parent.hasOnClickListeners()) &&
            parent.callOnClick()
    }

    private fun findView(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        val group = root as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findView(group.getChildAt(index), predicate)?.let { return it }
        }
        return null
    }

    private companion object {
        private val VIDEO_ACTIVITY_CLASSES = listOf(
            "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity",
            "tv.danmaku.bili.ui.video.VideoDetailsActivity",
            "com.bilibili.video.story.StoryVideoActivity",
        )
        private val LIKE_VIEW_IDS = listOf("frame_like", "like_layout")
        private val SCAN_DELAYS = longArrayOf(100L, 350L, 900L, 1800L)
    }
}
