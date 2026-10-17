package com.example.phone_number_lookup

/**
 * Recognises the same profile page arriving from different site lists.
 *
 * WhatsMyName calls a site "GitHub", Sherlock calls it "GitHub" and Tookie calls it "github.com".
 * De-duplicating by name alone never merged the Tookie row, so about one probe in five checked a
 * page that was already being checked, and a hit was shown two or three times.
 */
object SiteKey {

    private val PLACEHOLDERS = listOf("{account}", "{username}", "{}")
    private const val MARKER = "{u}"

    /** Same value for "https://www.github.com/{account}/", "http://github.com/{}" and similar. */
    fun of(profileTemplate: String): String {
        var t = profileTemplate.trim().lowercase()
        if (t.isEmpty()) return ""
        PLACEHOLDERS.forEach { t = t.replace(it, MARKER) }
        t = t.substringBefore('#')
        t = t.removePrefix("https://").removePrefix("http://")
        for (prefix in listOf("www.", "m.", "mobile.")) t = t.removePrefix(prefix)
        return t.trimEnd('/')
    }

    /**
     * Drops later rows that repeat an earlier site, so list order decides which copy survives
     * (WhatsMyName first, as the best-verified list). Rows are matched by name, and when
     * [byProfile] is true also by profile URL.
     */
    fun dedupe(sites: List<SiteProbe>, byProfile: Boolean = false): List<SiteProbe> {
        val seenNames = HashSet<String>()
        val seenProfiles = HashSet<String>()
        val out = ArrayList<SiteProbe>(sites.size)
        for (site in sites) {
            val name = site.name.trim().lowercase()
            if (name.isEmpty() || name in seenNames) continue
            val profile = if (byProfile) of(site.profileTemplate) else ""
            if (profile.isNotEmpty() && profile in seenProfiles) continue
            seenNames += name
            if (profile.isNotEmpty()) seenProfiles += profile
            out.add(site)
        }
        return out.sortedWith(compareBy({ it.category.lowercase() }, { it.name.lowercase() }))
    }
}
