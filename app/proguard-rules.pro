-keep class !org.mozilla.javascript.tools.**,org.mozilla.javascript.** { *; }
# Rhino's optional desktop debugging / shell tools are never loaded on Android.
-dontwarn org.mozilla.javascript.tools.**
# JavaBean introspection is an optional Rhino Java-object conversion path; Java objects are forbidden by ClassShutter.
-dontwarn java.beans.**
