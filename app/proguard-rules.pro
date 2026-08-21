# Keep the Xposed entry classes referenced from assets/xposed_init by name.
-keep class io.github.ringvibe.hook.** { *; }

# Xposed API is provided at runtime; do not warn about it.
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }
