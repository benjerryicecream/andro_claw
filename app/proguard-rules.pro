# AndroClaw ProGuard Rules

# Keep serialization classes
-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}

# Keep Room entities
-keep class com.androclaw.agent.data.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep accessibility service
-keep class com.androclaw.agent.perception.ClawAccessibilityService { *; }

# Keep agent service
-keep class com.androclaw.agent.agent.AgentService { *; }
