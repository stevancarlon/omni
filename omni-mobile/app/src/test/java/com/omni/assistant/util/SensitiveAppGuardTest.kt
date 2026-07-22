package com.omni.assistant.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused unit tests for [SensitiveAppGuard] matching logic.
 *
 * Covers known sensitive packages, similar-but-unrelated package names,
 * empty/malformed inputs, keyword matching, launcher detection, and
 * label allow/deny style matching (exact / token boundary, not substring
 * inside unrelated words).
 */
class SensitiveAppGuardTest {

    // ── isSensitive: known packages ──────────────────────────────────────

    @Test
    fun isSensitive_matchesKnownBankPackages() {
        assertTrue(SensitiveAppGuard.isSensitive("com.nu.production"))
        assertTrue(SensitiveAppGuard.isSensitive("br.com.bradesco"))
        assertTrue(SensitiveAppGuard.isSensitive("com.paypal.android.p2pmobile"))
        assertTrue(SensitiveAppGuard.isSensitive("com.chase.sig.android"))
        assertTrue(SensitiveAppGuard.isSensitive("com.google.android.apps.walletnfcrel"))
    }

    @Test
    fun isSensitive_isCaseSensitiveForExactKnownSet_butKeywordsAreCaseInsensitive() {
        // KNOWN_SENSITIVE uses exact set membership (case-sensitive).
        assertFalse(SensitiveAppGuard.isSensitive("COM.NU.PRODUCTION"))
        // Keyword path lowercases the input.
        assertTrue(SensitiveAppGuard.isSensitive("com.Example.BANK.client"))
        assertTrue(SensitiveAppGuard.isSensitive("com.Example.Wallet.App"))
    }

    // ── isSensitive: similar but unrelated ────────────────────────────────

    @Test
    fun isSensitive_rejectsSimilarButUnrelatedPackages() {
        // Looks like banking brands but is not in the known set and has no keyword.
        assertFalse(SensitiveAppGuard.isSensitive("com.example.myapp"))
        assertFalse(SensitiveAppGuard.isSensitive("com.nuproduction.tools")) // not exact known
        assertFalse(SensitiveAppGuard.isSensitive("org.mozilla.firefox"))
        assertFalse(SensitiveAppGuard.isSensitive("com.spotify.music"))
        // "itau" substring would match keyword — use packages without sensitive tokens
        assertFalse(SensitiveAppGuard.isSensitive("com.acme.calendar"))
        assertFalse(SensitiveAppGuard.isSensitive("com.android.settings"))
    }

    @Test
    fun isSensitive_matchesKeywordSubstrings() {
        assertTrue(SensitiveAppGuard.isSensitive("com.acme.crypto.trader"))
        assertTrue(SensitiveAppGuard.isSensitive("br.com.user.investimentos"))
        assertTrue(SensitiveAppGuard.isSensitive("com.foo.bitcoin.wallet"))
        assertTrue(SensitiveAppGuard.isSensitive("com.bar.brokerage"))
    }

    // ── isSensitive: empty / malformed ────────────────────────────────────

    @Test
    fun isSensitive_handlesEmptyAndMalformedInputs() {
        assertFalse(SensitiveAppGuard.isSensitive(""))
        assertFalse(SensitiveAppGuard.isSensitive("   "))
        assertFalse(SensitiveAppGuard.isSensitive("."))
        assertFalse(SensitiveAppGuard.isSensitive("..."))
        assertFalse(SensitiveAppGuard.isSensitive("not a package!!!"))
        // Synthetic garbage without sensitive keywords
        assertFalse(SensitiveAppGuard.isSensitive("com..double.dot"))
        assertFalse(SensitiveAppGuard.isSensitive("12345"))
    }

    // ── isLauncher ───────────────────────────────────────────────────────

    @Test
    fun isLauncher_matchesKnownLaunchersAndKeywords() {
        assertTrue(SensitiveAppGuard.isLauncher("com.google.android.apps.nexuslauncher"))
        assertTrue(SensitiveAppGuard.isLauncher("com.sec.android.app.launcher"))
        assertTrue(SensitiveAppGuard.isLauncher("com.miui.home"))
        // Keyword path
        assertTrue(SensitiveAppGuard.isLauncher("com.custom.superlauncher"))
        assertTrue(SensitiveAppGuard.isLauncher("com.vendor.home.shell"))
    }

    @Test
    fun isLauncher_rejectsNonLaunchers() {
        assertFalse(SensitiveAppGuard.isLauncher("com.nu.production"))
        assertFalse(SensitiveAppGuard.isLauncher("com.spotify.music"))
        assertFalse(SensitiveAppGuard.isLauncher(""))
        assertFalse(SensitiveAppGuard.isLauncher("com.example.calculator"))
    }

    // ── isSensitiveLabel: exact / boundary matching ───────────────────────

    @Test
    fun isSensitiveLabel_matchesExactAndTokenBoundaries() {
        assertTrue(SensitiveAppGuard.isSensitiveLabel("Nubank"))
        assertTrue(SensitiveAppGuard.isSensitiveLabel("nubank"))
        assertTrue(SensitiveAppGuard.isSensitiveLabel("Banco do Brasil"))
        assertTrue(SensitiveAppGuard.isSensitiveLabel("Mercado Pago"))
        // Token boundary: sensitive label as whole word
        assertTrue(SensitiveAppGuard.isSensitiveLabel("Open Nubank now"))
        assertTrue(SensitiveAppGuard.isSensitiveLabel("Nubank Wallet"))
        assertTrue(SensitiveAppGuard.isSensitiveLabel("My PicPay"))
    }

    @Test
    fun isSensitiveLabel_rejectsSubstringInsideUnrelatedWords() {
        // "nu" is a sensitive label, but should not match inside "manual" / "menu"
        // because matching requires exact token equality or space-delimited boundaries.
        assertFalse(SensitiveAppGuard.isSensitiveLabel("manual"))
        assertFalse(SensitiveAppGuard.isSensitiveLabel("menu"))
        assertFalse(SensitiveAppGuard.isSensitiveLabel("nucleus"))
        assertFalse(SensitiveAppGuard.isSensitiveLabel("interstellar")) // "inter" is a label; boundary required
        assertFalse(SensitiveAppGuard.isSensitiveLabel("clearly")) // "clear" is a label
    }

    @Test
    fun isSensitiveLabel_handlesEmptyMalformedAndPunctuation() {
        assertFalse(SensitiveAppGuard.isSensitiveLabel(""))
        assertFalse(SensitiveAppGuard.isSensitiveLabel("   "))
        assertFalse(SensitiveAppGuard.isSensitiveLabel("!!!"))
        assertFalse(SensitiveAppGuard.isSensitiveLabel("---"))
        // Punctuation is normalized to spaces; "nubank" remains a token
        assertTrue(SensitiveAppGuard.isSensitiveLabel("***Nubank***"))
        assertTrue(SensitiveAppGuard.isSensitiveLabel("  PicPay  "))
    }

    // ── Precedence documentation (deny-sensitive over benign) ─────────────

    @Test
    fun precedence_sensitivePackageBeatsBenignLookingName() {
        // Even if a package looks "normal", known set / keyword deny wins.
        assertTrue(SensitiveAppGuard.isSensitive("com.picpay"))
        assertTrue(SensitiveAppGuard.isSensitive("com.example.bank.helper"))
        // Launcher packages are not treated as sensitive banking apps by isSensitive
        // unless they also contain a sensitive keyword (they generally don't).
        assertFalse(SensitiveAppGuard.isSensitive("com.android.launcher3"))
        assertTrue(SensitiveAppGuard.isLauncher("com.android.launcher3"))
    }
}
