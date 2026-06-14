package io.github.bbzq.hooks

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.bbzq.ModuleSettings
import io.github.libxposed.api.XposedInterface
import org.json.JSONObject
import java.lang.reflect.Method

class FreeCopyHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    override fun startHook() {
        var count = hookCommentMenu()
        count += hookLongClickSources()
        count += hookConversation()
        log("Installed $count copy behavior hook(s)")
    }

    private fun hookCommentMenu(): Int {
        val holder = HostAccess.findClass(classLoader, COMMENT_MENU_HOLDER) ?: return 0
        val methods = HostAccess.methods(holder)
            .filter { method ->
                method.parameterTypes.any { View::class.java.isAssignableFrom(it) } &&
                    method.parameterTypes.any { it.name.contains("MenuItem") }
            }
            .toList()

        methods.forEach { method ->
            xposed.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    if (!ModuleSettings.isDisableLongPressCopyEnabled(prefs)) {
                        return@intercept chain.proceed()
                    }
                    val menuArg = method.parameterTypes.indices
                        .firstOrNull { method.parameterTypes[it].name.contains("MenuItem") }
                        ?.let(chain::getArg)
                    if (menuArg?.toString()?.contains("COPY", true) != true) {
                        return@intercept chain.proceed()
                    }

                    if (!ModuleSettings.isEnhanceLongPressCopyEnabled(prefs)) {
                        return@intercept defaultValue(method.returnType)
                    }

                    val result = chain.proceed()
                    val view = method.parameterTypes.indices
                        .firstOrNull { View::class.java.isAssignableFrom(method.parameterTypes[it]) }
                        ?.let { chain.getArg(it) as? View }
                    val text = readClipboard(view?.context) ?: findText(view)
                    if (!text.isNullOrBlank()) showCopyDialog(view?.context, text)
                    result
                }
        }
        return methods.size
    }

    private fun hookLongClickSources(): Int {
        var count = 0
        LONG_CLICK_CLASSES.mapNotNull { HostAccess.findClass(classLoader, it) }
            .distinct()
            .forEach { type ->
                HostAccess.methods(type)
                    .filter { method ->
                        method.name == "onLongClick" &&
                            method.parameterTypes.contentEquals(arrayOf(View::class.java))
                    }
                    .forEach { method ->
                        xposed.hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept { chain ->
                                if (!ModuleSettings.isDisableLongPressCopyEnabled(prefs)) {
                                    return@intercept chain.proceed()
                                }
                                if (!ModuleSettings.isEnhanceLongPressCopyEnabled(prefs)) {
                                    return@intercept true
                                }
                                val view = chain.getArg(0) as? View
                                val text = findText(view) ?: findTextOnTarget(chain.thisObject)
                                if (!text.isNullOrBlank()) showCopyDialog(view?.context, text)
                                true
                            }
                        count++
                    }
            }

        DESC_COPY_CLASSES.mapNotNull { HostAccess.findClass(classLoader, it) }
            .distinct()
            .forEach { type ->
                HostAccess.methods(type)
                    .filter { method ->
                        method.parameterTypes.any { View::class.java.isAssignableFrom(it) } &&
                            method.parameterTypes.any { it.name == "android.text.style.ClickableSpan" }
                    }
                    .forEach { method ->
                        xposed.hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept { chain ->
                                if (!ModuleSettings.isDisableLongPressCopyEnabled(prefs)) {
                                    return@intercept chain.proceed()
                                }
                                if (!ModuleSettings.isEnhanceLongPressCopyEnabled(prefs)) {
                                    return@intercept defaultValue(method.returnType)
                                }
                                val view = method.parameterTypes.indices
                                    .firstOrNull { View::class.java.isAssignableFrom(method.parameterTypes[it]) }
                                    ?.let { chain.getArg(it) as? View }
                                val text = findTextOnTarget(chain.thisObject) ?: findText(view)
                                if (!text.isNullOrBlank()) showCopyDialog(view?.context, text)
                                defaultValue(method.returnType)
                            }
                        count++
                    }
            }
        return count
    }

    private fun hookConversation(): Int {
        val activityClass = HostAccess.findClass(classLoader, CONVERSATION_ACTIVITY) ?: return 0
        val methods = HostAccess.methods(activityClass)
            .filter { it.parameterCount == 8 }
            .toList()
        methods.forEach { method ->
            xposed.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    if (!ModuleSettings.isEnhanceLongPressCopyEnabled(prefs)) {
                        return@intercept chain.proceed()
                    }
                    val action = chain.getArg(7)?.toString().orEmpty()
                    if (!action.contains("COPY", true) && chain.getArg(7) != chain.getArg(0)) {
                        return@intercept chain.proceed()
                    }
                    val activity = chain.thisObject as? Activity ?: return@intercept chain.proceed()
                    val message = chain.getArg(1) ?: return@intercept chain.proceed()
                    val text = extractMessageText(message) ?: return@intercept chain.proceed()
                    showCopyDialog(activity, text)
                    HostAccess.invoke(chain.getArg(6) ?: return@intercept null, "dismiss")
                    null
                }
        }
        return methods.size
    }

    private fun findText(view: View?): CharSequence? {
        if (view is TextView && !view.text.isNullOrBlank()) return view.text
        val group = view as? ViewGroup ?: return null
        COPYABLE_VIEW_IDS.forEach { name ->
            val id = group.resources.getIdentifier(name, "id", group.context.packageName)
            if (id != 0) {
                (group.findViewById<View>(id) as? TextView)?.text?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        for (index in 0 until group.childCount) {
            findText(group.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun findTextOnTarget(target: Any?): CharSequence? {
        if (target == null) return null
        return HostAccess.fields(target.javaClass)
            .mapNotNull { field -> runCatching { field.get(target) }.getOrNull() }
            .firstNotNullOfOrNull { value ->
                when (value) {
                    is SpannableStringBuilder -> value
                    is CharSequence -> value.takeIf { it.isNotBlank() }
                    else -> null
                }
            }
    }

    private fun extractMessageText(message: Any): String? {
        val raw = HostAccess.invoke(message, "getContentString") as? String ?: return null
        return runCatching {
            val json = JSONObject(raw)
            json.optString("content").ifBlank {
                buildList {
                    json.optString("title").takeIf(String::isNotBlank)?.let(::add)
                    json.optString("text").takeIf(String::isNotBlank)?.let(::add)
                    json.optJSONArray("modules")?.let { modules ->
                        for (index in 0 until modules.length()) {
                            val item = modules.optJSONObject(index) ?: continue
                            add(listOf(item.optString("title"), item.optString("detail"))
                                .filter(String::isNotBlank)
                                .joinToString("："))
                        }
                    }
                }.joinToString("\n")
            }
        }.getOrNull()
    }

    private fun readClipboard(context: Context?): CharSequence? {
        val clipboard = context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        return clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.takeIf { it.isNotBlank() }
    }

    private fun showCopyDialog(context: Context?, text: CharSequence) {
        val activity = context as? Activity ?: return
        activity.runOnUiThread {
            val dialog = AlertDialog.Builder(activity)
                .setTitle("自由复制内容")
                .setMessage(text)
                .setPositiveButton("分享") { _, _ ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text.toString())
                    }
                    activity.startActivity(Intent.createChooser(intent, "分享文本"))
                }
                .setNeutralButton("复制全部") { _, _ ->
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("bbzq_copy", text))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            dialog.findViewById<TextView>(android.R.id.message)?.apply {
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
            }
        }
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }

    private companion object {
        private const val COMMENT_MENU_HOLDER =
            "com.bilibili.app.comment3.ui.widget.menu.CommentMoreMenuItemHolder"
        private const val CONVERSATION_ACTIVITY =
            "com.bilibili.bplus.im.conversation.ConversationActivity"
        private val LONG_CLICK_CLASSES = listOf(
            "com.bilibili.app.comm.comment2.widget.CommentExpandableTextView\$OnLongClickListener",
            "com.bilibili.app.comment3.ui.widget.CommentLongClickListener",
        )
        private val DESC_COPY_CLASSES = listOf(
            "tv.danmaku.bili.ui.video.section.info.VideoDescCopyHelper",
            "com.bilibili.video.story.StoryTextCopyHelper",
            "com.bilibili.app.comm.comment2.helper.TextCopyHelper",
        )
        private val COPYABLE_VIEW_IDS = listOf(
            "message",
            "comment_message",
            "dy_card_text",
            "dy_opus_paragraph_desc",
            "dy_opus_paragraph_title",
            "dy_opus_paragraph_text",
        )
    }
}
