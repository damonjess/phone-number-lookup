package com.example.phone_number_lookup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleMetadataExtractorTest {

    @Test
    fun validEmailReturnsTruthfulNoIndexReport() = runBlocking {
        val report = GoogleMetadataExtractor.extract("user@example.com")

        assertEquals("user@example.com", report.email)
        assertFalse(report.accountActive)
        assertEquals("No public index found without direct Gaia ID", report.accountStatus)
        assertFalse(report.hasData)
    }

    @Test
    fun gmailAddressReturnsTruthfulNoIndexReport() = runBlocking {
        val report = GoogleMetadataExtractor.extract("alex.smith@gmail.com")

        assertEquals("alex.smith@gmail.com", report.email)
        assertFalse(report.accountActive)
        assertEquals("No public index found without direct Gaia ID", report.accountStatus)
        assertFalse(report.hasData)
    }

    @Test
    fun invalidEmailReturnsInvalidStatus() = runBlocking {
        val report = GoogleMetadataExtractor.extract("notanemail")

        assertEquals("notanemail", report.email)
        assertFalse(report.accountActive)
        assertEquals("Invalid email format", report.accountStatus)
    }

    @Test
    fun extractGaiaIdForNameFromPayloadMatchesContributorBlock() {
        val payload = """
            )]}'
            [["Alex Smith","https://lh3.googleusercontent.com/a-/ALV-UjXQDpAjnL_J6SetH4i4640oWBD52JydylGfaB8XyKRxgqaRpTTD3g=s120-c-rp-mo-br100",["https://www.google.com/maps/contrib/102672672532796901756?hl=en-GB"],"102672672532796901756"]]
        """.trimIndent()

        val gaiaId = GoogleMetadataExtractor.extractGaiaIdForNameFromPayload(payload, "Alex Smith")
        assertEquals("102672672532796901756", gaiaId)
    }

    @Test
    fun parseLocationHistoryMasJsonExtractsReviewsCommentsAndLocations() {
        // Build a representative `)]}'\n[...]` JSON structure matching `/locationhistory/preview/mas`
        val root = org.json.JSONArray()
        for (i in 0..45) root.put(org.json.JSONObject.NULL)

        // root[16]: Contributor profile
        val profileArr = org.json.JSONArray().apply {
            put("Alex Smith")
            val avatarContainer = org.json.JSONArray().apply {
                for (i in 0..5) put(org.json.JSONObject.NULL)
                put(org.json.JSONArray().put("https://lh3.googleusercontent.com/a-/avatar123"))
            }
            put(avatarContainer)
        }
        root.put(16, profileArr)

        // root[45][0]: Reviews list
        val reviewItem = org.json.JSONArray().apply {
            put("id-1")
            val placeOuter = org.json.JSONArray().apply {
                val placeInner = org.json.JSONArray().apply {
                    put("0x0:0x1")
                    put(org.json.JSONObject.NULL)
                    put(org.json.JSONObject.NULL)
                    put(org.json.JSONObject.NULL)
                    put("City Taxi Service")
                    put("123 Main St, London EC1A 1BB")
                }
                put(placeInner)
            }
            put(placeOuter)
            val revBlock = org.json.JSONArray().apply {
                put("rev-id")
                val timeBlock = org.json.JSONArray().apply {
                    put(org.json.JSONObject.NULL)
                    put(org.json.JSONObject.NULL)
                    put(1716546539453766L)
                    put(1716546539453766L)
                    put(org.json.JSONObject.NULL)
                    put(org.json.JSONObject.NULL)
                    put("Edited 10 months ago")
                }
                put(timeBlock)
                val ratingBlock = org.json.JSONArray().apply {
                    put(org.json.JSONArray().put(3))
                    for (i in 1..14) put(org.json.JSONObject.NULL)
                    val textArr = org.json.JSONArray().apply {
                        put(org.json.JSONArray().put("Great service overall."))
                    }
                    put(textArr)
                }
                put(ratingBlock)
                put(org.json.JSONObject.NULL)
                val shareBlock = org.json.JSONArray().apply {
                    put(org.json.JSONObject.NULL)
                    put(org.json.JSONObject.NULL)
                    put(org.json.JSONObject.NULL)
                    put("https://www.google.com/maps/reviews/data=!4m8!14m7!1m6!2m5!1sChdDSUhNMG9n")
                }
                put(shareBlock)
            }
            put(revBlock)
        }
        val reviewsWrapper = org.json.JSONArray().put(org.json.JSONArray().put(reviewItem))
        root.put(45, reviewsWrapper)

        val rawResponse = ")]}'\n" + root.toString()
        val parsed = GoogleMetadataExtractor.parseLocationHistoryMasJson("102672672532796901756", rawResponse)

        assertNotNull(parsed)
        assertEquals("Alex Smith", parsed!!.fullName)
        assertTrue(parsed.mappedLocations.contains("123 Main St, London EC1A 1BB, United Kingdom"))
        assertEquals(1, parsed.reviews.size)
        assertEquals("City Taxi Service", parsed.reviews[0].title)
        assertEquals("3/5 stars", parsed.reviews[0].ratingLabel)
        assertTrue(parsed.reviews[0].commentText.contains("Great service"))
    }
}
