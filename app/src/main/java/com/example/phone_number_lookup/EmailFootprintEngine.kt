package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

data class FootprintProfile(
    val email: String,
    val domainReport: EmailDomainReport? = null,
    val breachReport: BreachReport? = null,
    val registrationOutcome: EmailRegistrationOutcome? = null,
    val identityEnrichment: IdentityEnrichmentReport? = null,
    val handleUsernameHits: List<UsernameHit> = emptyList(),
    val error: String? = null
) {
    val totalFootprintCount: Int
        get() = (registrationOutcome?.registered?.size ?: 0) +
            (breachReport?.breachNames?.size ?: 0) +
            (identityEnrichment?.keybaseProofs?.size ?: 0) +
            (identityEnrichment?.pgpRecords?.size ?: 0) +
            handleUsernameHits.size
}

object EmailFootprintEngine {

    suspend fun profile(email: String): FootprintProfile = withContext(Dispatchers.IO) {
        val clean = email.trim().lowercase()
        if (clean.isBlank() || !clean.contains('@')) {
            return@withContext FootprintProfile(email, error = "Enter a valid email address")
        }

        val domainDef = async { EmailDomainScanner.check(clean) }
        val breachDef = async { EmailScanner.check(clean) }
        val regDef = async { EmailRegistrationScanner.scan(clean) }
        val identityDef = async { IdentityEnricher.enrich(clean, isEmail = true) }
        val handleDef = async {
            val handle = EmailRegistrationScanner.localPart(clean)
            if (handle != null) {
                UsernameScanner.scanWithVariants(handle, limit = UsernameScanner.QUICK_LIMIT).confirmed
            } else {
                emptyList()
            }
        }

        FootprintProfile(
            email = clean,
            domainReport = domainDef.await(),
            breachReport = breachDef.await(),
            registrationOutcome = regDef.await(),
            identityEnrichment = identityDef.await(),
            handleUsernameHits = handleDef.await()
        )
    }
}
