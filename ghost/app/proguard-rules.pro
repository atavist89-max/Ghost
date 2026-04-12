# ProGuard rules for Ghost app

# Keep LiteRT-LM classes
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }

# Keep for debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
