package com.buddy.assistant.util

import android.util.Log

class SpeechCorrector(appNames: List<String>) {

    private data class AppEntry(
        val original: String,
        val normalized: String,
        val consonants: String,
        val phonetic: String
    )

    private val entries: List<AppEntry> = appNames
        .filter { normalize(it).length >= 2 }
        .map { name ->
            val phonetic = phoneticNormalize(name)
            AppEntry(
                original = name,
                normalized = normalize(name),
                consonants = stripVowels(phonetic),
                phonetic = phonetic
            )
        }

    private val skipWords = setOf(
        "open", "play", "on", "the", "a", "an", "in", "my", "to", "and",
        "please", "hey", "buddy", "start", "launch", "close", "stop",
        "go", "show", "find", "search", "use", "run", "with", "for"
    )

    fun correct(text: String): String {
        val words = text.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return text

        val candidates = extractCandidates(words)
        var bestScore = 0.0
        var bestApp: AppEntry? = null
        var bestRange: IntRange? = null

        for ((phrase, range) in candidates) {
            for (app in entries) {
                val score = score(phrase, app)
                if (score > bestScore) {
                    bestScore = score
                    bestApp = app
                    bestRange = range
                }
            }
        }

        if (bestScore < THRESHOLD || bestApp == null || bestRange == null) return text

        Log.d(TAG, "'$text' -> replacing '${words.slice(bestRange).joinToString(" ")}' with '${bestApp.original}' (score=${"%.2f".format(bestScore)})")

        val result = words.toMutableList()
        result[bestRange.first] = bestApp.original
        for (i in bestRange.last downTo bestRange.first + 1) {
            result.removeAt(i)
        }
        return result.joinToString(" ")
    }

    // ─── Candidate Extraction ────────────────────────────────────────────────

    private fun extractCandidates(words: List<String>): List<Pair<String, IntRange>> {
        val candidates = mutableListOf<Pair<String, IntRange>>()
        for (len in 1..minOf(3, words.size)) {
            for (start in 0..words.size - len) {
                val range = start until start + len
                val phrase = words.slice(range).joinToString("")
                // Skip single common command words
                if (len == 1 && words[start] in skipWords) continue
                candidates.add(phrase to range)
            }
        }
        return candidates
    }

    // ─── Scoring ─────────────────────────────────────────────────────────────

    private fun score(candidate: String, app: AppEntry): Double {
        val candidateNorm = normalize(candidate)
        val candidatePhonetic = phoneticNormalize(candidate)
        val candidateConsonants = stripVowels(candidatePhonetic)

        if (candidateNorm == app.normalized) return 1.0

        val levenSim = levenshteinSimilarity(candidateNorm, app.normalized)
        val consonantSim = levenshteinSimilarity(candidateConsonants, app.consonants)
        val phoneticSim = levenshteinSimilarity(candidatePhonetic, app.phonetic)
        val startsWithBonus = when {
            candidateNorm.startsWith(app.normalized) -> 0.15
            app.normalized.startsWith(candidateNorm) -> 0.15
            else -> 0.0
        }

        return (levenSim * 0.25) + (consonantSim * 0.30) + (phoneticSim * 0.30) + startsWithBonus
    }

    // ─── String Normalization ────────────────────────────────────────────────

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun stripVowels(s: String): String =
        s.replace(Regex("[aeiou]"), "")

    private fun phoneticNormalize(s: String): String {
        var r = s.lowercase().replace(Regex("[^a-z]"), "")
        r = r.replace("eye", "i")
        r = r.replace("igh", "i")
        r = r.replace("ph", "f")
        r = r.replace("ght", "t")
        r = r.replace("ck", "k")
        r = r.replace("wh", "w")
        r = r.replace("wr", "r")
        r = r.replace("kn", "n")
        r = r.replace("ee", "i")
        r = r.replace("oo", "u")
        r = r.replace("ou", "u")
        r = r.replace(Regex("(.)\\1+"), "$1")
        return r
    }

    // ─── Levenshtein ─────────────────────────────────────────────────────────

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
            }
        }
        return dp[a.length][b.length]
    }

    private fun levenshteinSimilarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val maxLen = maxOf(a.length, b.length)
        return 1.0 - (levenshtein(a, b).toDouble() / maxLen)
    }

    companion object {
        private const val TAG = "SpeechCorrector"
        private const val THRESHOLD = 0.45
    }
}
