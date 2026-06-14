package io.github.bbzq.roaming

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

class MethodHookParam internal constructor(
    private val chain: XposedInterface.Chain,
    val executable: Executable,
    val thisObject: Any?,
    val args: MutableList<Any?>,
) {
    private var returnEarly = false
    private var resultValue: Any? = null

    var result: Any?
        get() = resultValue
        set(value) {
            resultValue = value
            returnEarly = true
        }

    internal fun setInitialResult(value: Any?) {
        resultValue = value
        returnEarly = false
    }

    internal fun shouldReturnEarly(): Boolean = returnEarly

    fun invokeOriginalMethod(): Any? = chain.proceed(args.toTypedArray())
}

typealias Hooker = (MethodHookParam) -> Unit
typealias Replacer = (MethodHookParam) -> Any?

fun RoamingEnv.hookBefore(executable: Executable, hooker: Hooker) {
    executable.isAccessible = true
    xposed.hook(executable)
        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
        .intercept { chain ->
            val param = MethodHookParam(
                chain = chain,
                executable = executable,
                thisObject = chain.getThisObject(),
                args = chain.getArgs().toMutableList(),
            )
            hooker(param)
            if (param.shouldReturnEarly()) {
                param.result
            } else {
                chain.proceed(param.args.toTypedArray())
            }
        }
}

fun RoamingEnv.hookAfter(executable: Executable, hooker: Hooker) {
    executable.isAccessible = true
    xposed.hook(executable)
        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
        .intercept { chain ->
            val param = MethodHookParam(
                chain = chain,
                executable = executable,
                thisObject = chain.getThisObject(),
                args = chain.getArgs().toMutableList(),
            )
            param.setInitialResult(chain.proceed(param.args.toTypedArray()))
            hooker(param)
            param.result
        }
}

fun RoamingEnv.replace(executable: Executable, replacer: Replacer) {
    executable.isAccessible = true
    xposed.hook(executable)
        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
        .intercept { chain ->
            val param = MethodHookParam(
                chain = chain,
                executable = executable,
                thisObject = chain.getThisObject(),
                args = chain.getArgs().toMutableList(),
            )
            replacer(param)
        }
}

fun RoamingEnv.hookBeforeMethod(
    type: Class<*>,
    methodName: String,
    vararg parameterTypes: Class<*>,
    hooker: Hooker,
): Int {
    val method = type.findMethodOrNull(methodName, *parameterTypes) ?: return 0
    hookBefore(method, hooker)
    return 1
}

fun RoamingEnv.hookAfterMethod(
    type: Class<*>,
    methodName: String,
    vararg parameterTypes: Class<*>,
    hooker: Hooker,
): Int {
    val method = type.findMethodOrNull(methodName, *parameterTypes) ?: return 0
    hookAfter(method, hooker)
    return 1
}

fun RoamingEnv.replaceMethod(
    type: Class<*>,
    methodName: String,
    vararg parameterTypes: Class<*>,
    replacer: Replacer,
): Int {
    val method = type.findMethodOrNull(methodName, *parameterTypes) ?: return 0
    replace(method, replacer)
    return 1
}

fun RoamingEnv.hookBeforeAllMethods(type: Class<*>, methodName: String?, hooker: Hooker): Int {
    val methods = type.allMethods()
        .filter { methodName == null || it.name == methodName }
        .distinctBy(Method::toGenericString)
        .toList()
    methods.forEach { hookBefore(it, hooker) }
    return methods.size
}

fun RoamingEnv.hookAfterAllMethods(type: Class<*>, methodName: String?, hooker: Hooker): Int {
    val methods = type.allMethods()
        .filter { methodName == null || it.name == methodName }
        .distinctBy(Method::toGenericString)
        .toList()
    methods.forEach { hookAfter(it, hooker) }
    return methods.size
}

fun RoamingEnv.hookBeforeAllConstructors(type: Class<*>, hooker: Hooker): Int {
    val constructors = type.declaredConstructors.onEach { it.isAccessible = true }
    constructors.forEach { hookBefore(it, hooker) }
    return constructors.size
}

fun RoamingEnv.hookAfterAllConstructors(type: Class<*>, hooker: Hooker): Int {
    val constructors = type.declaredConstructors.onEach { it.isAccessible = true }
    constructors.forEach { hookAfter(it, hooker) }
    return constructors.size
}

fun RoamingEnv.hookBeforeConstructor(
    type: Class<*>,
    vararg parameterTypes: Class<*>,
    hooker: Hooker,
): Int {
    val constructor = runCatching {
        type.getDeclaredConstructor(*parameterTypes).apply { isAccessible = true }
    }.getOrNull() ?: return 0
    hookBefore(constructor, hooker)
    return 1
}

private fun Class<*>.findMethodOrNull(name: String, vararg parameterTypes: Class<*>): Method? {
    return runCatching {
        getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
    }.getOrNull() ?: methodsNamed(name).firstOrNull {
        it.name == name && it.parameterTypes.contentEquals(parameterTypes)
    }
}
