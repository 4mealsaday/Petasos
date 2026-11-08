# Preserve the separate instrumentation APK's runner, registry, probes, and app API names.
-dontshrink
-dontobfuscate
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class com.petasos.app.** { *; }
