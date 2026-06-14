package io.github.bbzq.hooks

class PackageLoadLogHook(
    targetPackageName: String,
) : BaseHook(targetPackageName) {
    override fun startHook() {
        log("Observed $packageName after classloader became ready")
    }
}
