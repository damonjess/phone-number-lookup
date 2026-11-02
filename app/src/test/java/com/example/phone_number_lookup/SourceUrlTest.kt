package com.example.phone_number_lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceUrlTest {
    @Test
    fun preservesDeepGithubIssueUrl() {
        val url = "https://github.com/spyboy-productions/CamXploit/pull/22"
        assertEquals(url, OsintDatasets.preserveSourceUrl(url))
    }

    @Test
    fun preservesDeepGoogleMapsReviewUrl() {
        val url = "https://www.google.com/maps/contrib/123456789012345678901/reviews"
        assertEquals(url, OsintDatasets.preserveSourceUrl(url))
    }

    @Test
    fun rejectsNonHttpAndMalformedUrls() {
        assertNull(OsintDatasets.preserveSourceUrl("javascript:alert(1)"))
        assertNull(OsintDatasets.preserveSourceUrl("not a url"))
    }
}
