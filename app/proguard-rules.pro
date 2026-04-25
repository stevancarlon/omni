# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep accessibility service
-keep class com.omni.assistant.service.OmniAccessibilityService { *; }

# Keep data classes used with Gson
-keep class com.omni.assistant.data.** { *; }
-keep class com.omni.assistant.llm.LLMResponse { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
