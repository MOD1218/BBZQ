package io.github.bbzq.feats.hook

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.MethodHookParam
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allFields
import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.getObjectField
import io.github.bbzq.feats.replace
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale

class DownloadThreadHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (!ModuleSettings.isCustomDownloadThreadEnabled(prefs)) return

        var count = 0
        findListenerTypes().forEach { type ->
            count += hookListener(type)
        }
        count += hookReportDownloadThread()

        if (count > 0) {
            log("startHook: DownloadThread, methods=$count")
        } else {
            log("startHook: DownloadThread, no matching hooks found")
        }
    }

    private fun hookListener(type: Class<*>): Int {
        var count = 0

        type.declaredConstructors
            .filter { ctor -> ctor.parameterTypes.any { it == TextView::class.java } }
            .forEach { ctor ->
                env.hookBefore(ctor) { param ->
                    val view = param.args.firstOrNull { it is TextView } as? TextView ?: return@hookBefore
                    val parent = view.parent as? ViewGroup ?: return@hookBefore
                    val visibility = if (view.tag as? Int == 1) {
                        view.text = CUSTOM_LABEL
                        View.VISIBLE
                    } else {
                        View.INVISIBLE
                    }
                    parent.getChildAt(1)?.visibility = visibility
                }
                count++
            }

        val onClick = type.allMethods().firstOrNull {
            it.name == "onClick" &&
                it.parameterCount == 1 &&
                View::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: return count

        env.hookBefore(onClick) { param ->
            val view = param.thisObject?.findFieldValue(TextView::class.java) ?: return@hookBefore
            if (view.tag as? Int != 1) return@hookBefore

            applyCustomConcurrency(view, param)
            param.result = null
        }
        count++
        return count
    }

    private fun hookReportDownloadThread(): Int {
        val method = findReportDownloadThreadMethod() ?: return 0
        if (method.returnType.isPrimitive) return 0
        env.replace(method) { null }
        return 1
    }

    private fun applyCustomConcurrency(view: TextView, param: MethodHookParam) {
        view.tag = ModuleSettings.getCustomDownloadConcurrency(prefs)
        runCatching { param.invokeOriginalMethod() }
            .onFailure { log("DownloadThread onClick failed", it) }
    }

    private fun findTextViewFieldValue(target: Any, type: Class<out TextView>): TextView? {
        return target.javaClass.allFields().firstNotNullOfOrNull { field ->
            if (!type.isAssignableFrom(field.type)) return@firstNotNullOfOrNull null
            runCatching { field.get(target) as? TextView }.getOrNull()
        }
    }

    private fun Any?.findFieldValue(type: Class<out TextView>): TextView? {
        val target = this ?: return null
        return findTextViewFieldValue(target, type)
    }

    private fun findListenerTypes(): List<Class<*>> {
        val scoped = findClasses { name ->
            val lower = name.lowercase(Locale.US)
            lower.contains("download") || lower.contains("thread") || lower.contains("listener")
        }.filter { it.isDownloadThreadListener() }

        return if (scoped.isNotEmpty()) scoped else findClasses { true }.filter { it.isDownloadThreadListener() }
    }

    private fun findReportDownloadThreadMethod(): Method? {
        val scoped = findReportDownloadThreadMethod(false)
        return scoped ?: findReportDownloadThreadMethod(true)
    }

    private fun findReportDownloadThreadMethod(broad: Boolean): Method? {
        val classes = findClasses { name ->
            if (broad) return@findClasses true
            val lower = name.lowercase(Locale.US)
            lower.contains("download") || lower.contains("thread") || lower.contains("report")
        }
        return classes.asSequence()
            .flatMap { type -> type.allMethods().asSequence() }
            .firstOrNull { method ->
                method.name == "reportDownloadThread" &&
                    method.parameterCount == 2 &&
                    method.parameterTypes[0] == Context::class.java &&
                    method.parameterTypes[1] == Int::class.javaPrimitiveType
            }
    }

    private fun findClasses(predicate: (String) -> Boolean): List<Class<*>> {
        return dexClassNames()
            .filter(predicate)
            .mapNotNull { name -> runCatching { Class.forName(name, false, classLoader) }.getOrNull() }
            .distinctBy { it.name }
            .toList()
    }

    private fun dexClassNames(): Sequence<String> = sequence {
        val baseDexClassLoader = classLoader as? BaseDexClassLoader ?: return@sequence
        val pathList = baseDexClassLoader.getObjectField("pathList") ?: return@sequence
        val dexElements = pathList.getObjectField("dexElements") as? Array<*> ?: return@sequence
        dexElements.forEach { element ->
            val dexFile = element?.getObjectField("dexFile") as? DexFile ?: return@forEach
            val entries = dexFile.entries()
            while (entries.hasMoreElements()) {
                yield(entries.nextElement())
            }
        }
    }

    private fun Class<*>.isDownloadThreadListener(): Boolean {
        if (isInterface || Modifier.isAbstract(modifiers)) return false
        val hasTextViewConstructor = declaredConstructors.any { ctor ->
            ctor.parameterTypes.any { TextView::class.java.isAssignableFrom(it) }
        }
        val hasOnClick = allMethods().any { method ->
            method.name == "onClick" &&
                method.parameterCount == 1 &&
                View::class.java.isAssignableFrom(method.parameterTypes[0])
        }
        return hasTextViewConstructor && hasOnClick
    }

    private companion object {
        private const val CUSTOM_LABEL = "自定义"
    }
}
