package io.github.ringvibe.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.ringvibe.core.Prefs

/**
 * Single Xposed entry point (declared in assets/xposed_init). LSPosed calls this once per hooked
 * process; we branch on the package name:
 *
 *  - "android"  -> the system_server / framework process: install notification + call detection.
 *  - our own app -> flip [io.github.ringvibe.core.StatusProbe] so the settings screen shows "active".
 *  - RingConn app -> install the Bluetooth capture + inject hooks.
 */
class ModuleEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName

        when (pkg) {
            "android" -> safe("system detection") { SystemHooks.install(lpparam) }

            Prefs.MODULE_PACKAGE -> safe("self status probe") {
                XposedHelpers.findAndHookMethod(
                    "io.github.ringvibe.core.StatusProbe",
                    lpparam.classLoader,
                    "isModuleActive",
                    XC_MethodReplacement.returnConstant(true),
                )
            }

            else -> safe("ringconn hooks") { RingConnHooks.maybeInstall(lpparam) }
        }
    }

    private inline fun safe(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            XLog.e("failed to install $what", t)
        }
    }
}
