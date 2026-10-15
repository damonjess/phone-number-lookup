package com.example.phone_number_lookup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneInfogaBuiltInTest {

    private val uk = DorkNumber("+442079460958", "+44 20 7946 0958", "020 7946 0958", "02079460958")

    @Test
    fun theRangeLabelIsTheFirstSixDigitsPlusXxxx() {
        assertEquals("020794xxxx", PhoneInfogaBuiltIn.askedRange("02079460958"))
        // Same rule PhoneInfoga uses: its own test maps 0365172812 to 036517xxxx.
        assertEquals("036517xxxx", PhoneInfogaBuiltIn.askedRange("0365172812"))
        assertNull(PhoneInfogaBuiltIn.askedRange("012345"))
        assertNull(PhoneInfogaBuiltIn.askedRange("123"))
    }

    @Test
    fun aNumberInAnOvhRangeIsReported() {
        val body = """[{"number":"036517xxxx","city":"Lyon","zipCode":"69000","country":"fr"},
                       {"number":"099999xxxx","city":"X","zipCode":"1"}]"""
        val scan = PhoneInfogaBuiltIn.interpretOvh(200, body, "0365172812")
        assertEquals(PhoneInfogaStatus.OK, scan.status)
        assertTrue(scan.rows.contains("Found" to "yes"))
        assertTrue(scan.rows.contains("Number range" to "036517xxxx"))
        assertTrue(scan.rows.contains("City" to "Lyon"))
        assertTrue(scan.rows.contains("Zip code" to "69000"))
        assertTrue(scan.message!!.contains("VoIP"))
    }

    @Test
    fun aNumberOutsideEveryRangeSaysSoAndStatesTheLimit() {
        val scan = PhoneInfogaBuiltIn.interpretOvh(200, """[{"number":"099999xxxx"}]""", "02079460958")
        assertEquals(PhoneInfogaStatus.OK, scan.status)
        assertTrue(scan.rows.contains("Found" to "no"))
        assertTrue(scan.message!!.contains("only covers OVH"))
    }

    @Test
    fun ovhErrorsAreReportedNotTreatedAsNotFound() {
        val rejected = PhoneInfogaBuiltIn.interpretOvh(
            400, """{"message":"[country] Given data (co) does not belong to the NumberCountryEnum enumeration"}""", "0123456789"
        )
        assertEquals(PhoneInfogaStatus.ERROR, rejected.status)
        assertTrue(rejected.message!!.contains("[country]"))

        assertEquals(PhoneInfogaStatus.ERROR, PhoneInfogaBuiltIn.interpretOvh(-1, "", "0123456789").status)
        assertEquals(PhoneInfogaStatus.ERROR, PhoneInfogaBuiltIn.interpretOvh(200, "<html>hi</html>", "0123456789").status)
        assertEquals(PhoneInfogaStatus.ERROR, PhoneInfogaBuiltIn.interpretOvh(500, "", "0123456789").status)
    }

    @Test
    fun aTooShortNumberIsSkippedNotReportedAsNotFound() {
        val scan = PhoneInfogaBuiltIn.interpretOvh(200, "[]", "012345")
        assertEquals(PhoneInfogaStatus.SKIPPED, scan.status)
    }

    @Test
    fun theDorkScanListsEveryDorkAsALabelledLink() {
        val scan = PhoneInfogaBuiltIn.dorkScan(uk)
        assertEquals("googlesearch", scan.scanner)
        assertEquals(15, scan.labeledLinks.size)
        assertTrue(scan.labeledLinks.all { it.second.startsWith("https://www.google.com/search?q=") })
        assertTrue(scan.labeledLinks.any { it.first == "Scam and spam reports: WhoCallsMe" })
        assertEquals(5, scan.rows.size)
        assertTrue(scan.rows.contains("Social media" to "1 search"))
        assertTrue(scan.rows.contains("Documents and pages" to "5 searches"))
    }

    @Test
    fun aSupportedCountryAsksOvhForItsRegionInLowerCase() = runBlocking {
        val asked = mutableListOf<String>()
        val report = PhoneInfogaBuiltIn.runWith(uk, "GB", 44) { url ->
            asked += url
            HttpResponse(200, """[{"number":"020794xxxx","city":"London","zipCode":"EC1"}]""")
        }
        assertEquals(listOf("https://api.ovh.com/1.0/telephony/number/detailedZones?country=gb"), asked)
        assertEquals(listOf("profile-links", "ovh", "googlesearch"), report.scans.map { it.scanner })
        assertTrue(report.scans[1].rows.contains("City" to "London"))
        assertNull(report.error)
        assertTrue(report.note!!.contains("no server"))
    }

    @Test
    fun anUnsupportedCountryNeverCallsOvh() = runBlocking {
        var called = false
        val us = DorkNumber("+12125550123", "+1 212-555-0123", "(212) 555-0123", "2125550123")
        val report = PhoneInfogaBuiltIn.runWith(us, "US", 1) { called = true; HttpResponse(200, "[]") }
        assertEquals(false, called)
        assertEquals(PhoneInfogaStatus.SKIPPED, report.scans[1].status)
        assertTrue(report.scans[1].message!!.contains("+1"))
        assertEquals(PhoneInfogaStatus.OK, report.scans[0].status) // direct links still work
        assertEquals(PhoneInfogaStatus.OK, report.scans[2].status) // dorks still work
    }

    @Test
    fun anOvhOutageStillLeavesTheDorksUsable() = runBlocking {
        val report = PhoneInfogaBuiltIn.runWith(uk, "GB", 44) { HttpResponse(-1, "") }
        assertEquals(PhoneInfogaStatus.ERROR, report.scans[1].status)
        assertEquals(PhoneInfogaStatus.OK, report.scans[0].status)
        assertEquals(PhoneInfogaStatus.OK, report.scans[2].status)
    }

    @Test
    fun aMissingRegionSkipsOvh() = runBlocking {
        val report = PhoneInfogaBuiltIn.runWith(uk, null, 44) { error("must not be called") }
        assertEquals(PhoneInfogaStatus.SKIPPED, report.scans[1].status)
    }

    @Test
    fun theProfileLinksScanOpensWhatsAppAndTellowsForTheNumber() {
        val scan = PhoneInfogaBuiltIn.linksScan("+447795504158")
        assertEquals("profile-links", scan.scanner)
        assertEquals(PhoneInfogaStatus.OK, scan.status)
        assertEquals(
            listOf("https://wa.me/447795504158", "https://www.tellows.co.uk/num/%2B447795504158"),
            scan.labeledLinks.map { it.second }
        )
        assertTrue(scan.message!!.contains("no public link"))
    }

    @Test
    fun aNumberTooShortForLinksIsSkipped() {
        assertEquals(PhoneInfogaStatus.SKIPPED, PhoneInfogaBuiltIn.linksScan("+1").status)
    }
}
