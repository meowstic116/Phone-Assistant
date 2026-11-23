# Shrink, but do not rename anything: the on-device crash notes in Diagnostics read stack
# traces, and the bundled sherpa-onnx bindings resolve their native methods by name.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# JNI: every native method of the vendored sherpa-onnx Kotlin bindings must survive
# shrinking with its exact name and descriptor.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# The app uses no reflection, so the default optimize rules may remove anything
# unreachable. Apache Ant's tar/bzip2 readers and the org.json parser are reached
# directly from app code and are kept by reachability.
