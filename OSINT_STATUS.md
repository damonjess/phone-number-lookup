# OSINT feature status

The app contains several different kinds of OSINT features. They are intentionally not all presented as verified identity lookups.

## Live in-app checks

- **Tellows:** the app requests the public caller-reputation response and parses its score, report count, location, and caller type when returned.
- **DuckDuckGo web search:** the app requests a public HTML search and extracts result titles, snippets, and links. Results may be blocked or incomplete.
- **GitHub OSINT:** the app uses GitHub's public REST endpoints for usernames and repositories. Unauthenticated requests are rate-limited.
- **Username scanner:** the app probes the public endpoints described by the merged site datasets. Sites that block, time out, or return ambiguous responses are reported separately where supported.
- **Email breach check:** the app sends the entered email to XposedOrNot and displays the response. A negative response is not proof that the address has never appeared in a breach.

## Browser-launcher tools

The PhoneInfoga, Phonia, IntelBase, and several external reverse-lookup buttons open a Google search or third-party website. They do not return a verified result inside the app. A page opening, a WhatsApp/Telegram link, or a search result does not prove ownership or identity.

## Estimates

Country, area, time zone, and line type are derived from public numbering metadata and prefixes. They are not live carrier verification and do not prove where a caller is located.

## Interpretation rule

Treat an exact-number report from a named source as evidence about that source's user reports only. Treat no result as "not found by the checked source," not as proof that the number is safe or unassociated with a person.
