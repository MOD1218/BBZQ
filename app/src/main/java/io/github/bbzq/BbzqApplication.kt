package io.github.bbzq

import android.app.Application

class BbzqApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (LinkerGuard.hasConflict(this)) {
            LinkerGuard.triggerConflict()
            return
        }
        ModuleRemotePreferences.init(this)
        applyDesktopIconSetting()
    }

    private fun applyDesktopIconSetting() {
        val prefs = getSharedPreferences(ModuleSettings.PREFS_NAME, MODE_PRIVATE)
        val hideIcon = ModuleSettings.isHideDesktopIconEnabled(prefs)
        DesktopIconHelper.applySetting(this, hideIcon)
    }
}
