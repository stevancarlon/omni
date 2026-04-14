# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep accessibility service
-keep class com.buddy.assistant.service.BuddyAccessibilityService { *; }

# Keep data classes used with Gson
-keep class com.buddy.assistant.data.** { *; }
-keep class com.buddy.assistant.llm.LLMResponse { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
