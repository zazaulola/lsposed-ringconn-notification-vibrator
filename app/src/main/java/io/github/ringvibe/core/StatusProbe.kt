package io.github.ringvibe.core

/**
 * Lets the settings screen tell whether the module is actually loaded by LSPosed.
 *
 * [isModuleActive] returns false as compiled. When the module is active and our own app package is
 * in the LSPosed scope, [io.github.ringvibe.hook.ModuleEntry] hooks this method to return true, so a
 * `true` at runtime proves the hook framework is injecting our code.
 */
object StatusProbe {
    @JvmStatic
    fun isModuleActive(): Boolean = false
}
