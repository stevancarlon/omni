package com.omni.assistant.util

object SensitiveAppGuard {

    private val KNOWN_SENSITIVE = setOf(
        "com.nu.production",
        "br.com.bradesco",
        "br.com.bradesco.next",
        "com.itau",
        "br.com.itau",
        "br.com.itau.pers",
        "br.com.bb.android",
        "com.santander.app",
        "br.com.santander",
        "br.com.intermedium",
        "br.com.original.bank",
        "br.com.c6bank.app",
        "br.com.xp.carteira",
        "br.com.btgpactual",
        "br.com.rico",
        "br.com.clear",
        "br.com.sicredi",
        "br.com.sicoobnet",
        "com.picpay",
        "com.mercadopago.wallet",
        "br.gov.caixa.tem",
        "br.com.caixa",
        "com.chase.sig.android",
        "com.bankofamerica.cashpromobile",
        "com.wf.wellsfargomobile",
        "com.citi.citimobile",
        "com.paypal.android.p2pmobile",
        "com.venmo",
        "com.revolut.revolut",
        "com.wise.android",
        "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.nbu.paisa.user",
    )

    private val SENSITIVE_KEYWORDS = listOf(
        "bank",
        "banco",
        "banque",
        "banca",
        "finance",
        "finanz",
        "financ",
        "wallet",
        "carteira",
        "trading",
        "invest",
        "broker",
        "crypto",
        "bitcoin",
        "bradesco",
        "itau",
        "itaú",
        "santander",
        "nubank",
        "caixa",
        "sicredi",
        "sicoob",
        "btg",
    )

    private val SENSITIVE_LABELS = listOf(
        "nubank",
        "nu",
        "bradesco",
        "next",
        "itau",
        "itaú",
        "santander",
        "banco do brasil",
        "bb",
        "inter",
        "c6",
        "caixa",
        "picpay",
        "mercado pago",
        "xp",
        "btg",
        "rico",
        "clear",
        "sicredi",
        "sicoob",
    )

    private val LAUNCHER_PACKAGES = setOf(
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.google.android.apps.pixel.launcher",
        "com.android.launcher",
        "com.android.launcher2",
        "com.android.launcher3",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.vivo.launcher",
        "com.oneplus.launcher",
    )

    fun isSensitive(packageName: String): Boolean {
        if (packageName in KNOWN_SENSITIVE) return true
        val lower = packageName.lowercase()
        return SENSITIVE_KEYWORDS.any { lower.contains(it) }
    }

    fun isLauncher(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return lower in LAUNCHER_PACKAGES ||
            lower.contains("launcher") ||
            lower.contains("home")
    }

    fun isSensitiveLabel(label: String): Boolean {
        val normalized = label
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        if (normalized.isBlank()) return false

        return SENSITIVE_LABELS.any { sensitive ->
            normalized == sensitive ||
                normalized.contains(" $sensitive ") ||
                normalized.startsWith("$sensitive ") ||
                normalized.endsWith(" $sensitive")
        }
    }
}
