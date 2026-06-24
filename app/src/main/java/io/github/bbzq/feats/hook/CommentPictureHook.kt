package io.github.bbzq.feats.hook

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import io.github.bbzq.ModuleSettings
import io.github.bbzq.R
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allFields
import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.hookAfter
import java.util.concurrent.ConcurrentHashMap

class CommentPictureHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        var count = 0
        val methods = env.symbols?.commentPicture?.restore(classLoader)?.initViewMethods.orEmpty()
        methods.forEach { method ->
            env.hookAfter(method) { param ->
                runCatching {
                    if (!ModuleSettings.isCommentPictureViewEnabled(prefs)) return@runCatching
                    val rootView = param.args.firstOrNull() as? View ?: return@runCatching
                    val fragment = param.thisObject ?: return@runCatching
                    injectPopupMenu(fragment, rootView)
                }.onFailure {
                    log("Comment picture hook failed at ${method.declaringClass.name}.${method.name}", it)
                }
            }
        }
        count += methods.size
        log("startHook: CommentPicture, methods=$count")
    }

    private fun injectPopupMenu(fragment: Any, root: View) {
        val moreButtonId = root.resources.getIdentifier("more_button", "id", root.context.packageName)
        if (moreButtonId == 0) return
        val moreButton = root.findViewById<View>(moreButtonId) ?: return
        if (moreButton.getTag(TAG_COMMENT_PICTURE_HOOKED) == true) return

        val originalListener = resolveOriginalClickListener(moreButton)
        moreButton.setTag(TAG_COMMENT_PICTURE_HOOKED, true)
        moreButton.setOnClickListener { view ->
            showPopupMenu(fragment, view, originalListener)
        }
    }

    private fun showPopupMenu(fragment: Any, anchor: View, originalListener: View.OnClickListener?) {
        PopupMenu(anchor.context, anchor).apply {
            menu.add(
                anchor.context.getString(R.string.comment_picture_view_open_label),
            ).setOnMenuItemClickListener {
                openImage(anchor, extractCurrentImageUrl(fragment))
                true
            }
            menu.add(
                anchor.context.getString(R.string.comment_picture_view_share_label),
            ).setOnMenuItemClickListener {
                originalListener?.onClick(anchor)
                true
            }
        }.show()
    }

    private fun openImage(view: View, url: String?) {
        if (url.isNullOrBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            if (view.context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { view.context.startActivity(intent) }
            .onFailure {
                log("Comment picture open failed for $url", it)
                Toast.makeText(
                    view.context,
                    view.context.getString(R.string.comment_picture_view_open_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
    }

    private fun resolveOriginalClickListener(view: View): View.OnClickListener? {
        return runCatching {
            val listenerInfoField = View::class.java.getDeclaredField("mListenerInfo").apply {
                isAccessible = true
            }
            val listenerInfo = listenerInfoField.get(view) ?: return null
            val clickListenerField = listenerInfo.javaClass.getDeclaredField("mOnClickListener").apply {
                isAccessible = true
            }
            clickListenerField.get(listenerInfo) as? View.OnClickListener
        }.getOrNull()
    }

    private fun extractCurrentImageUrl(fragment: Any): String? {
        val pagerManager = invokeBestCandidate(
            fragment,
            preferredNames = listOf("getCardPagerManager", "cardPagerManager"),
            include = listOf("card", "pager"),
        ) ?: findFieldValue(
            fragment,
            listOf("CardPagerManager"),
            listOf("card", "pager"),
        ) ?: return null

        val currentCard = invokeBestCandidate(
            pagerManager,
            preferredNames = listOf("getCurrentCardFragment", "currentCardFragment"),
            include = listOf("current", "card"),
        ) ?: findFieldValue(
            pagerManager,
            listOf("CardFragment"),
            listOf("current", "card"),
        ) ?: return null

        val imageItem = invokeBestCandidate(
            currentCard,
            preferredNames = listOf("getCurrentImageItem", "currentImageItem", "getImageItem"),
            include = listOf("image", "item"),
        ) ?: findFieldValue(
            currentCard,
            listOf("CommentImageItem"),
            listOf("image", "item"),
        ) ?: return null

        return MediaUrlResolver.resolve(imageItem)
    }

    private fun invokeBestCandidate(
        target: Any,
        preferredNames: List<String>,
        include: List<String>,
    ): Any? {
        val methods = target.javaClass.allMethods()
            .filter { it.parameterCount == 0 && it.returnType != Void.TYPE }
            .toList()

        preferredNames.forEach { name ->
            methods.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.let { return runCatching { it.invoke(target) }.getOrNull() }
        }

        return methods.firstOrNull { method ->
            val lowerName = method.name.lowercase()
            include.all(lowerName::contains)
        }?.let { runCatching { it.invoke(target) }.getOrNull() }
    }

    private fun findFieldValue(target: Any, preferredTypeNames: List<String>, include: List<String>): Any? {
        val fields = target.javaClass.allFields().toList()

        preferredTypeNames.forEach { typeName ->
            fields.firstOrNull { it.type.name.contains(typeName, ignoreCase = true) }
                ?.let { return runCatching { it.get(target) }.getOrNull() }
        }

        return fields.firstOrNull { field ->
            val lowerName = field.name.lowercase()
            include.all(lowerName::contains)
        }?.let { runCatching { it.get(target) }.getOrNull() }
    }

    private companion object {
        private const val TAG_COMMENT_PICTURE_HOOKED = -0x21b10c

    }
}

private object MediaUrlResolver {
    private val stringFieldCache = ConcurrentHashMap<Class<*>, java.lang.reflect.Field?>()

    fun resolve(item: Any?): String? {
        if (item == null) return null
        return resolveByGetter(item)
            ?: resolveByFieldHeuristic(item)
    }

    private fun resolveByGetter(item: Any): String? {
        return runCatching {
            item.javaClass.allMethods()
                .firstOrNull { method ->
                    method.parameterCount == 0 &&
                        method.returnType == String::class.java &&
                        method.name.contains("url", ignoreCase = true)
                }
                ?.invoke(item) as? String
        }.getOrNull()
    }

    private fun resolveByFieldHeuristic(item: Any): String? {
        val field = stringFieldCache.getOrPut(item.javaClass) {
            item.javaClass.allFields()
                .firstOrNull {
                    it.type == String::class.java &&
                        it.name.contains("url", ignoreCase = true)
                }
                ?: item.javaClass.allFields().firstOrNull { it.type == String::class.java }
        }
        return runCatching { field?.get(item) as? String }.getOrNull()
    }
}
