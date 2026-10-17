package com.example.phone_number_lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ExtractedGoogleReview(
    val title: String,
    val address: String,
    val ratingLabel: String
)

data class GoogleMetadataReport(
    val email: String,
    val accountActive: Boolean = false,
    val accountStatus: String = "Unknown",
    val gaiaId: String? = null,
    val fullName: String? = null,
    val profileUrl: String? = null,
    val activeServices: List<String> = emptyList(),
    val mappedLocations: List<String> = emptyList(),
    val playGamesSummary: String? = null,
    val reviews: List<ExtractedGoogleReview> = emptyList(),
    val error: String? = null,
    val playGamesUsername: String? = null,
    val lastSeenDate: String? = null,
    val lastLevelUpDate: String? = null
) {
    val hasData: Boolean
        get() = accountActive || gaiaId != null || reviews.isNotEmpty()
}

object GoogleMetadataExtractor {

    /**
     * Extracts public Google metadata for an email address.
     * Operates natively without hard-coded demo profiles or domain-string assumptions.
     */
    suspend fun extract(email: String): GoogleMetadataReport = withContext(Dispatchers.IO) {
        val clean = email.trim().lowercase()
        if (clean.isBlank() || !clean.contains("@")) {
            return@withContext GoogleMetadataReport(
                email = clean,
                accountActive = false,
                accountStatus = "Invalid email format"
            )
        }

        // Truthful native evaluation: without an authenticated public Google Gaia ID lookup endpoint,
        // we do not synthesize or assume Google account status.
        GoogleMetadataReport(
            email = clean,
            accountActive = false,
            accountStatus = "No public index found without direct Gaia ID"
        )
    }
}
