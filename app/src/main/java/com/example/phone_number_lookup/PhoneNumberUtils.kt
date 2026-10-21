package com.example.phone_number_lookup

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType
import java.util.Locale

data class NumberFormats(
    val e164: String,
    val international: String,
    val national: String,
    val rfc3966: String
)

object PhoneNumberUtils {
    private val util = PhoneNumberUtil.getInstance()
    private val cleanRegex = Regex("[^0-9+]")

    private val ukAreaCodes = mapOf(
        "020" to "London",
        "023" to "Southampton & Portsmouth",
        "024" to "Coventry",
        "028" to "Northern Ireland",
        "029" to "Cardiff",
        "0113" to "Leeds",
        "0114" to "Sheffield",
        "0115" to "Nottingham",
        "0116" to "Leicester",
        "0117" to "Bristol",
        "0118" to "Reading",
        "0121" to "Birmingham",
        "0131" to "Edinburgh",
        "0141" to "Glasgow",
        "0151" to "Liverpool",
        "0161" to "Manchester",
        "0191" to "Newcastle upon Tyne, Sunderland & Durham",
        "01200" to "Clitheroe",
        "01202" to "Bournemouth & Poole",
        "01204" to "Bolton",
        "01205" to "Boston",
        "01206" to "Colchester",
        "01207" to "Consett",
        "01208" to "Bodmin",
        "01209" to "Redruth",
        "01223" to "Cambridge",
        "01224" to "Aberdeen",
        "01225" to "Bath",
        "01226" to "Barnsley",
        "01227" to "Canterbury",
        "01228" to "Carlisle",
        "01229" to "Barrow-in-Furness",
        "01233" to "Ashford (Kent)",
        "01234" to "Bedford",
        "01235" to "Abingdon",
        "01241" to "Arbroath",
        "01242" to "Cheltenham",
        "01243" to "Chichester",
        "01244" to "Chester",
        "01245" to "Chelmsford",
        "01246" to "Chesterfield",
        "01248" to "Bangor (Gwynedd)",
        "01249" to "Chippenham",
        "01252" to "Aldershot & Farnborough",
        "01253" to "Blackpool",
        "01254" to "Blackburn",
        "01255" to "Clacton-on-Sea",
        "01256" to "Basingstoke",
        "01268" to "Basildon",
        "01270" to "Crewe",
        "01271" to "Barnstaple",
        "01273" to "Brighton & Hove",
        "01274" to "Bradford",
        "01276" to "Camberley",
        "01277" to "Brentwood",
        "01278" to "Bridgwater",
        "01279" to "Harlow & Bishop's Stortford",
        "01282" to "Burnley",
        "01283" to "Burton upon Trent",
        "01284" to "Bury St Edmunds",
        "01285" to "Cirencester",
        "01291" to "Chepstow",
        "01292" to "Ayr",
        "01293" to "Crawley & Gatwick",
        "01296" to "Aylesbury",
        "01302" to "Doncaster",
        "01303" to "Folkestone",
        "01304" to "Dover",
        "01305" to "Dorchester & Weymouth",
        "01322" to "Dartford",
        "01323" to "Eastbourne",
        "01324" to "Falkirk",
        "01325" to "Darlington",
        "01326" to "Falmouth",
        "01332" to "Derby",
        "01344" to "Bracknell",
        "01352" to "Mold",
        "01353" to "Ely",
        "01355" to "East Kilbride",
        "01376" to "Braintree",
        "01380" to "Devizes",
        "01382" to "Dundee",
        "01383" to "Dunfermline",
        "01384" to "Dudley & Stourbridge",
        "01386" to "Evesham",
        "01387" to "Dumfries",
        "01392" to "Exeter",
        "01403" to "Horsham",
        "01422" to "Halifax",
        "01423" to "Harrogate",
        "01424" to "Hastings",
        "01432" to "Hereford",
        "01438" to "Stevenage",
        "01442" to "Hemel Hempstead",
        "01444" to "Haywards Heath",
        "01452" to "Gloucester",
        "01453" to "Stroud",
        "01462" to "Hitchin & Letchworth",
        "01463" to "Inverness",
        "01472" to "Grimsby",
        "01473" to "Ipswich",
        "01474" to "Gravesend",
        "01475" to "Greenock",
        "01476" to "Grantham",
        "01480" to "Huntingdon",
        "01482" to "Hull",
        "01483" to "Guildford & Woking",
        "01484" to "Huddersfield",
        "01494" to "High Wycombe",
        "01506" to "Livingston",
        "01509" to "Loughborough",
        "01522" to "Lincoln",
        "01524" to "Lancaster",
        "01527" to "Redditch",
        "01536" to "Kettering",
        "01539" to "Kendal",
        "01543" to "Cannock & Lichfield",
        "01553" to "King's Lynn",
        "01562" to "Kidderminster",
        "01563" to "Kilmarnock",
        "01582" to "Luton",
        "01603" to "Norwich",
        "01604" to "Northampton",
        "01606" to "Northwich",
        "01622" to "Maidstone",
        "01623" to "Mansfield",
        "01625" to "Macclesfield",
        "01628" to "Maidenhead",
        "01633" to "Newport",
        "01634" to "Medway (Chatham, Gillingham & Rochester)",
        "01635" to "Newbury",
        "01636" to "Newark-on-Trent",
        "01642" to "Middlesbrough & Stockton-on-Tees",
        "01664" to "Melton Mowbray",
        "01670" to "Morpeth",
        "01689" to "Orpington",
        "01698" to "Motherwell & Hamilton",
        "01702" to "Southend-on-Sea",
        "01704" to "Southport",
        "01706" to "Rochdale",
        "01707" to "Welwyn Garden City & Hatfield",
        "01708" to "Romford",
        "01709" to "Rotherham",
        "01722" to "Salisbury",
        "01723" to "Scarborough",
        "01724" to "Scunthorpe",
        "01727" to "St Albans",
        "01732" to "Sevenoaks",
        "01733" to "Peterborough",
        "01737" to "Redhill & Reigate",
        "01738" to "Perth",
        "01743" to "Shrewsbury",
        "01744" to "St Helens",
        "01752" to "Plymouth",
        "01753" to "Slough & Windsor",
        "01754" to "Skegness",
        "01756" to "Skipton",
        "01757" to "Selby",
        "01763" to "Royston",
        "01768" to "Penrith",
        "01772" to "Preston",
        "01775" to "Spalding",
        "01777" to "Retford",
        "01780" to "Stamford",
        "01782" to "Stoke-on-Trent",
        "01784" to "Staines-upon-Thames",
        "01785" to "Stafford",
        "01786" to "Stirling",
        "01788" to "Rugby",
        "01789" to "Stratford-upon-Avon",
        "01792" to "Swansea",
        "01793" to "Swindon",
        "01795" to "Sittingbourne",
        "01803" to "Torquay & Paignton",
        "01823" to "Taunton",
        "01827" to "Tamworth",
        "01844" to "Thame",
        "01858" to "Market Harborough",
        "01865" to "Oxford",
        "01872" to "Truro",
        "01889" to "Rugeley & Uttoxeter",
        "01892" to "Royal Tunbridge Wells",
        "01895" to "Uxbridge",
        "01902" to "Wolverhampton",
        "01903" to "Worthing",
        "01904" to "York",
        "01905" to "Worcester",
        "01908" to "Milton Keynes",
        "01909" to "Worksop",
        "01922" to "Walsall",
        "01923" to "Watford",
        "01924" to "Wakefield & Dewsbury",
        "01925" to "Warrington",
        "01926" to "Warwick & Leamington Spa",
        "01932" to "Weybridge",
        "01933" to "Wellingborough",
        "01934" to "Weston-super-Mare",
        "01935" to "Yeovil",
        "01942" to "Wigan",
        "01946" to "Whitehaven",
        "01952" to "Telford",
        "01962" to "Winchester",
        "01977" to "Pontefract & Castleford",
        "01978" to "Wrexham",
        "01992" to "Waltham Cross & Cheshunt",
        "01993" to "Witney"
    )

    private val ukMobileBlockCarriers = mapOf(
        "070" to "Ofcom Personal Numbering (Call-Forwarding / High Tariff Risk)",
        "073" to "Hutchison 3G (Three UK) / MVNO range",
        "07400" to "Hutchison 3G (Three UK)",
        "07401" to "Telefonica UK (O2)",
        "07402" to "Telefonica UK (O2)",
        "07403" to "Telefonica UK (O2)",
        "07406" to "Vodafone UK",
        "07411" to "Vodafone UK",
        "07415" to "EE (Everything Everywhere)",
        "0742" to "Vodafone / Telefonica / Lycamobile range",
        "0744" to "Telefonica UK (O2) / C&W range",
        "0745" to "Hutchison 3G (Three UK) / Lycamobile range",
        "0746" to "Vodafone UK range",
        "0747" to "Vodafone UK / EE range",
        "0748" to "Telefonica UK (O2) / Telecom2 range",
        "0749" to "Hutchison 3G (Three UK) / Truphone range",
        "0750" to "EE / Vodafone UK range",
        "0751" to "Telefonica UK (O2) / EE range",
        "0752" to "Telefonica UK (O2) / Vodafone range",
        "0753" to "EE / Vodafone UK range",
        "0754" to "Telefonica UK (O2) range",
        "0755" to "Vodafone UK / EE range",
        "0756" to "Telefonica UK (O2) / Virgin Mobile range",
        "0757" to "EE (T-Mobile) / Vodafone range",
        "0758" to "Vodafone UK / Telefonica (O2) range",
        "0759" to "Telefonica UK (O2) / EE range",
        // 07624 is Isle of Man mobile, NOT a pager
        "07624" to "Manx Telecom / Sure (Isle of Man Mobile)",
        // Ofcom 076 Radiopaging allocations
        "07600" to "PageOne Communications (Radiopaging)",
        "07623" to "PageOne Communications (Radiopaging)",
        "07640" to "Vodafone Paging (Radiopaging)",
        "07659" to "PageOne Communications (Radiopaging)",
        "07660" to "PageOne Communications (Radiopaging)",
        "07663" to "Vodafone Paging (Radiopaging)",
        "07666" to "Vodafone Limited / 24 Seven Communications / FIO Telecom (Radiopaging)",
        "07669" to "Vodafone Paging (Radiopaging)",
        "07677" to "PageOne Communications (Radiopaging)",
        "07681" to "PageOne Communications (Radiopaging)",
        "07693" to "Vodafone Paging (Radiopaging)",
        "07699" to "Vodafone Paging (Radiopaging)",
        "076" to "Ofcom Radiopaging Allocation (Receive-only pager range)",
        "077009" to "Ofcom TV & Drama Reserved Range (Fake / Spoofed CLI)",
        "07701" to "Telefonica UK (O2)",
        "07702" to "Vodafone UK",
        "07703" to "Vodafone UK",
        "0771" to "Vodafone UK / Telefonica (O2)",
        "0772" to "Vodafone UK / EE",
        "0773" to "Vodafone UK / Telefonica (O2)",
        "0774" to "Vodafone UK",
        "0775" to "Vodafone UK",
        "0776" to "Telefonica UK (O2)",
        "0777" to "Vodafone UK / EE (Orange)",
        "0778" to "Vodafone UK / EE (Orange)",
        "07790" to "EE (Orange)",
        "07791" to "EE (Orange)",
        "07792" to "EE (Orange)",
        "07793" to "Telefonica UK (O2)",
        "07794" to "EE (Orange)",
        "07795" to "Vodafone UK",
        "07796" to "Vodafone UK",
        "07797" to "JT (Jersey)",
        "07798" to "Vodafone UK",
        "07799" to "Vodafone UK",
        "0780" to "Telefonica UK (O2) / EE",
        "0781" to "EE (Orange)",
        "0782" to "Vodafone UK",
        "0783" to "Vodafone UK",
        "0784" to "Telefonica UK (O2) / Vodafone",
        "0785" to "Vodafone UK / Telefonica (O2)",
        "0786" to "Vodafone UK / Telefonica (O2)",
        "0787" to "Telefonica UK (O2) / Vodafone",
        "0788" to "Vodafone UK / EE",
        "0789" to "Telefonica UK (O2) / Virgin Mobile",
        "0790" to "Telefonica UK (O2) / EE",
        "0791" to "Vodafone UK / EE",
        "0792" to "Telefonica UK (O2) / Vodafone",
        "0793" to "EE (T-Mobile)",
        "0794" to "EE (T-Mobile)",
        "0795" to "EE (T-Mobile)",
        "0796" to "EE (T-Mobile / Orange)",
        "0797" to "EE (Orange)",
        "0798" to "EE (T-Mobile / Orange)",
        "0799" to "Vodafone UK",
        "056" to "Ofcom Location-Independent Electronic Communications (VoIP)"
    )

    fun isUkDramaOrUnallocated(normalized: String): Boolean {
        if (!normalized.startsWith("+44")) return false
        val localPart = "0" + normalized.removePrefix("+44")
        return localPart.startsWith("01632") ||
            localPart.startsWith("07700900") ||
            localPart.startsWith("08081570") ||
            localPart.startsWith("09098790") ||
            localPart.startsWith("03069990") ||
            localPart.startsWith("071") ||
            localPart.startsWith("072")
    }

    fun normalize(input: String): String {
        val cleaned = input.trim().replace(cleanRegex, "")
        if (cleaned.isBlank()) return ""
        val international = if (cleaned.startsWith("00")) "+${cleaned.drop(2)}" else cleaned
        return try {
            val parsed = util.parse(international, if (international.startsWith("+")) null else "GB")
            if (util.isPossibleNumber(parsed)) util.format(parsed, PhoneNumberFormat.E164) else international
        } catch (_: Exception) {
            international
        }
    }

    fun isValidUk(normalized: String): Boolean = try {
        val parsed = util.parse(normalized, "GB")
        util.getRegionCodeForNumber(parsed) == "GB" && util.isValidNumber(parsed)
    } catch (_: Exception) {
        false
    }

    fun getCountry(normalized: String): String {
        val parsed = try {
            util.parse(normalized, if (normalized.startsWith("+")) null else "GB")
        } catch (_: Exception) {
            null
        }
        val region = parsed?.let { util.getRegionCodeForNumber(it) }
        return when (region) {
            "GB" -> "United Kingdom (+44)"
            "US", "CA" -> "United States / Canada (+1)"
            "FR" -> "France (+33)"
            "DE" -> "Germany (+49)"
            "ES" -> "Spain (+34)"
            "IT" -> "Italy (+39)"
            "AU" -> "Australia (+61)"
            "IN" -> "India (+91)"
            "JP" -> "Japan (+81)"
            "CN" -> "China (+86)"
            "RU", "KZ" -> "Russia / Kazakhstan (+7)"
            "BR" -> "Brazil (+55)"
            else -> {
                if (!region.isNullOrBlank() && parsed != null) {
                    val display = Locale("", region).displayCountry
                    if (display.isNotBlank() && display != region) {
                        "$display (+${parsed.countryCode})"
                    } else {
                        "International (+${parsed.countryCode})"
                    }
                } else {
                    "International / Unknown"
                }
            }
        }
    }

    fun getTimezone(normalized: String): String = when {
        normalized.startsWith("+44") -> "Europe/London (GMT/BST)"
        normalized.startsWith("+353") -> "Europe/Dublin (GMT/IST)"
        normalized.startsWith("+1") -> "North American time zones"
        normalized.startsWith("+33") -> "Europe/Paris (CET/CEST)"
        normalized.startsWith("+49") -> "Europe/Berlin (CET/CEST)"
        normalized.startsWith("+34") -> "Europe/Madrid (CET/CEST)"
        normalized.startsWith("+39") -> "Europe/Rome (CET/CEST)"
        normalized.startsWith("+31") -> "Europe/Amsterdam (CET/CEST)"
        normalized.startsWith("+32") -> "Europe/Brussels (CET/CEST)"
        normalized.startsWith("+41") -> "Europe/Zurich (CET/CEST)"
        normalized.startsWith("+48") -> "Europe/Warsaw (CET/CEST)"
        normalized.startsWith("+91") -> "Asia/Kolkata (IST)"
        normalized.startsWith("+92") -> "Asia/Karachi (PKT)"
        normalized.startsWith("+61") -> "Australian time zones"
        normalized.startsWith("+64") -> "Pacific/Auckland (NZST/NZDT)"
        normalized.startsWith("+81") -> "Asia/Tokyo (JST)"
        normalized.startsWith("+86") -> "Asia/Shanghai (CST)"
        normalized.startsWith("+234") -> "Africa/Lagos (WAT)"
        normalized.startsWith("+27") -> "Africa/Johannesburg (SAST)"
        else -> "UTC / Unknown time zone"
    }

    fun getLineType(normalized: String): String {
        val localPart = if (normalized.startsWith("+44")) "0" + normalized.removePrefix("+44") else normalized
        when {
            isUkDramaOrUnallocated(normalized) ->
                return "Reserved drama or unallocated UK range (guaranteed spoofed CLI)"
            localPart.startsWith("070") ->
                return "Estimated personal-forwarding number (high scam & tariff risk)"
            localPart.startsWith("07624") ->
                return "Estimated mobile number (Isle of Man)"
            localPart.startsWith("076") ->
                return "Estimated radiopaging (pager) number — receive-only (cannot make outbound calls; spoofed CLI or callback trap)"
            localPart.startsWith("07") ->
                return "Estimated mobile number"
            localPart.startsWith("0800") || localPart.startsWith("0808") ->
                return "Estimated freephone number"
            localPart.startsWith("084") || localPart.startsWith("087") ->
                return "Estimated special-rate number"
            localPart.startsWith("09") ->
                return "Estimated premium-rate number"
            localPart.startsWith("056") ->
                return "Estimated VoIP (internet) number"
            localPart.startsWith("030") ->
                return "Estimated non-geographic number (public sector / charity)"
            localPart.startsWith("03") ->
                return "Estimated non-geographic number"
            localPart.startsWith("01") || localPart.startsWith("02") ->
                return "Estimated geographic landline"
        }

        val parsed = try {
            util.parse(normalized, if (normalized.startsWith("+")) null else "GB")
        } catch (_: Exception) {
            null
        } ?: return "Type not determined"

        return when (util.getNumberType(parsed)) {
            PhoneNumberType.MOBILE -> "Estimated mobile number"
            PhoneNumberType.FIXED_LINE -> "Estimated geographic landline"
            PhoneNumberType.FIXED_LINE_OR_MOBILE -> "Estimated landline or mobile number"
            PhoneNumberType.TOLL_FREE -> "Estimated freephone number"
            PhoneNumberType.PREMIUM_RATE -> "Estimated premium-rate number"
            PhoneNumberType.SHARED_COST -> "Estimated special-rate number"
            PhoneNumberType.VOIP -> "Estimated VoIP (internet) number"
            PhoneNumberType.PERSONAL_NUMBER -> "Estimated personal-forwarding number"
            PhoneNumberType.PAGER -> "Estimated radiopaging number"
            PhoneNumberType.UAN -> "Estimated non-geographic number"
            else -> "Type not determined"
        }
    }

    fun getAreaInfo(normalized: String): String? {
        if (!normalized.startsWith("+44")) return null
        val localPart = "0" + normalized.removePrefix("+44")
        if (isUkDramaOrUnallocated(normalized)) {
            return "Ofcom TV/drama or unallocated range (never assigned to real subscribers)"
        }
        for (length in 6 downTo 3) {
            if (localPart.length >= length) {
                ukAreaCodes[localPart.take(length)]?.let { return it }
            }
        }
        return when {
            localPart.startsWith("070") -> "UK 070 personal-number forwarding range (high scam/cost risk)"
            localPart.startsWith("07624") -> "Isle of Man mobile range"
            localPart.startsWith("076") -> "UK 076 pager range (receive-only; outbound calls from 076 are spoofed)"
            localPart.startsWith("07") -> "UK mobile range"
            localPart.startsWith("0800") || localPart.startsWith("0808") -> "UK freephone range"
            localPart.startsWith("030") -> "UK public sector & charity non-geographic range"
            localPart.startsWith("03") -> "UK national-rate non-geographic range"
            localPart.startsWith("056") -> "UK location-independent VoIP range"
            localPart.startsWith("084") || localPart.startsWith("087") -> "UK service-rate range"
            localPart.startsWith("09") -> "UK premium-rate range"
            else -> "UK geographic region not mapped"
        }
    }

    /** Offline Ofcom numbering block lookup (shows original range holder before number portability). */
    fun getUkCarrierHint(normalized: String): String? {
        if (!normalized.startsWith("+44")) return null
        val localPart = "0" + normalized.removePrefix("+44")
        for (len in 6 downTo 3) {
            if (localPart.length >= len) {
                ukMobileBlockCarriers[localPart.take(len)]?.let { return "$it (original Ofcom block)" }
            }
        }
        return when {
            localPart.startsWith("01") || localPart.startsWith("02") ->
                "UK Geographic PSTN / Wholesale Carrier Block (BT / Openreach / Virgin / Gamma)"
            localPart.startsWith("0800") || localPart.startsWith("0808") ->
                "Ofcom Freephone Allocation"
            localPart.startsWith("03") ->
                "Ofcom UK-wide Non-Geographic Allocation"
            else -> null
        }
    }

    fun getFormattedFormats(normalized: String, localNumber: String): NumberFormats {
        return try {
            val parsed = util.parse(normalized, null)
            NumberFormats(
                e164 = util.format(parsed, PhoneNumberFormat.E164),
                international = util.format(parsed, PhoneNumberFormat.INTERNATIONAL),
                national = util.format(parsed, PhoneNumberFormat.NATIONAL),
                rfc3966 = util.format(parsed, PhoneNumberFormat.RFC3966)
            )
        } catch (_: Exception) {
            val digits = normalized.filter { it.isDigit() }
            NumberFormats("+$digits", "+$digits", localNumber, "tel:+$digits")
        }
    }
}
