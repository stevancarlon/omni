package com.omni.assistant.util

object SensitiveAppGuard {

    private val KNOWN_SENSITIVE = setOf(
        "com.nu.production",
        "br.com.bradesco",
        "com.itau",
        "br.com.bb.android",
        "com.santander.app",
        "br.com.intermedium",
        "br.com.original.bank",
        "br.com.c6bank.app",
        "com.picpay",
        "com.mercadopago.wallet",
        "br.gov.caixa.tem",
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
    )

    fun isSensitive(packageName: String): Boolean {
        if (packageName in KNOWN_SENSITIVE) return true
        val lower = packageName.lowercase()
        return SENSITIVE_KEYWORDS.any { lower.contains(it) }
    }
}
