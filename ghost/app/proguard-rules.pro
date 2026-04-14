# ProGuard rules for Ghost app

# Keep LiteRT-LM classes
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }

# Keep Tavily data classes for Gson serialization
-keep class com.ghost.app.inference.TavilySearchService$SearchResponse {
    <fields>;
}
-keep class com.ghost.app.inference.TavilySearchService$SearchResult {
    <fields>;
}
-keepclassmembers class com.ghost.app.inference.TavilySearchService$* {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep for debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
