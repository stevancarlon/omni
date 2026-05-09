package com.omni.assistant.llm

import java.util.Locale

enum class TargetAppProfile(val atom: String) {
    GOOGLE_MAPS(":google_maps"),
    YOUTUBE_MUSIC(":youtube_music"),
    YOUTUBE(":youtube"),
    IFOOD(":ifood"),
}

object AppPromptProfile {
    fun infer(goal: String, foregroundPackage: String): TargetAppProfile? {
        val haystack = "${goal.lowercase(Locale.ROOT)} ${foregroundPackage.lowercase(Locale.ROOT)}"
        return when {
            hasAny(haystack, "google maps", "maps", "route", "directions", "navigate", "navigation", "drive to", "go to") ||
                foregroundPackage == "com.google.android.apps.maps" -> TargetAppProfile.GOOGLE_MAPS

            hasAny(haystack, "youtube music", "yt music", "play music") ||
                foregroundPackage == "com.google.android.apps.youtube.music" -> TargetAppProfile.YOUTUBE_MUSIC

            hasAny(haystack, "youtube", "you tube", "video") ||
                foregroundPackage == "com.google.android.youtube" -> TargetAppProfile.YOUTUBE

            hasAny(haystack, "ifood", "i food", "order food", "delivery", "restaurant") ||
                foregroundPackage == "br.com.brainweb.ifood" -> TargetAppProfile.IFOOD

            else -> null
        }
    }

    fun rulesFor(profile: TargetAppProfile?): String? = when (profile) {
        TargetAppProfile.GOOGLE_MAPS -> googleMapsRules()
        TargetAppProfile.YOUTUBE_MUSIC -> youtubeMusicRules()
        TargetAppProfile.YOUTUBE -> youtubeRules()
        TargetAppProfile.IFOOD -> ifoodRules()
        null -> null
    }

    private fun googleMapsRules() = """
        ═══ APP PROFILE ${TargetAppProfile.GOOGLE_MAPS.atom} — GOOGLE MAPS ═══
        The user usually wants to complete navigation, not explore place details.
        - If the goal is to start a route/navigation to a city/place, the priority is: find/select destination → Directions if needed → Start.
        - If the screen shows a destination bottom sheet with Start visible, tap Start immediately. Do not tap Share, Save, bookmark, photos, place cards, category chips, or overflow controls.
        - If both Directions and Start are visible, Start wins. It means the route is ready.
        - If Start is not visible but Directions is visible, tap Directions.
        - Avoid opening place-detail modals unless the destination is not selected yet or required route controls are missing.
        - The row with Restaurants, Attractions, Hotels, Share, Save, and similar chips is not progress for route-start tasks.
        - Once navigation has started or the route is active, return done(success=true).
    """.trimIndent()

    private fun youtubeMusicRules() = """
        ═══ APP PROFILE ${TargetAppProfile.YOUTUBE_MUSIC.atom} — YOUTUBE MUSIC ═══
        The user usually wants playback to begin.
        - Prefer Play, Shuffle, the top song/result, or the most direct media result over filters, overflow menus, radio settings, or account controls.
        - If a search result matching the requested song/artist/playlist is visible, tap the result or its primary play action.
        - Once playback is visibly active, return done(success=true).
    """.trimIndent()

    private fun youtubeRules() = """
        ═══ APP PROFILE ${TargetAppProfile.YOUTUBE.atom} — YOUTUBE ═══
        The user usually wants a video opened or played.
        - Prefer Search, the best matching video result, and visible Play controls.
        - Do not open channel pages, filters, captions, Share, Save, or overflow menus unless required by the user.
        - If the requested video/content is playing or opened, return done(success=true).
    """.trimIndent()

    private fun ifoodRules() = """
        ═══ APP PROFILE ${TargetAppProfile.IFOOD.atom} — IFOOD ═══
        The user usually wants to find/order food with minimum exploration.
        - Prefer search, restaurant/product results, Add, Continue, Checkout, Confirm, and Pay actions.
        - Do not open filters, sort sheets, coupons, profile, loyalty, or informational modals unless required by the goal.
        - If a modal blocks the ordering flow and is not required, dismiss it.
        - Stop when the order is placed or when required user-sensitive payment/address confirmation needs manual input.
    """.trimIndent()

    private fun hasAny(text: String, vararg needles: String): Boolean {
        return needles.any { text.contains(it) }
    }
}
