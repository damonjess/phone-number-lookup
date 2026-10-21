# OSINT feature status

The app contains several different kinds of OSINT features. They are intentionally not all presented as verified identity lookups.

## Live in-app checks

- **PhoneInfoga-compatible native modules:** the local scanner is automatic; Numverify carrier/line data is available through an optional apilayer key entered in the app; public footprint results use the app's native web-search request.
- **Tellows:** the app requests the public caller-reputation response and parses its score, report count, location, and caller type when returned.
- **DuckDuckGo web search:** the app requests exact-number, spam-term, and caller-directory-focused public HTML searches and extracts result titles, snippets, and links. Directory-focused terms include Tellows, Who Called UK, Should I Answer, WhoCallsMe, and Who’s Ringing. Results may be blocked, incomplete, or absent from the search index.
- **GitHub OSINT:** the app uses GitHub's public REST endpoints for usernames and repositories. Unauthenticated requests are rate-limited.
- **Username scanner:** the app embeds upstream Sherlock, Tookie-OSINT, and Blackbird Python scan cores through Chaquopy. The selected tool or all three can be run in-process; results are rendered by the Android UI rather than the upstream terminal interface.
- **Email account discovery:** the app calls the upstream Blackbird Python email scan core. The email's local part is also checked against public username profiles using the selected upstream Python scanner.
- **Google public-calendar check:** reports only whether the email-specific public calendar URL returned a readable calendar and, if present, its calendar display name. This does not prove that the email owns an active Google account.
- **Email breach check:** the app sends the entered email to XposedOrNot and displays the response. A negative response is not proof that the address has never appeared in a breach.

These scan cores are the actual upstream Python projects, not a Termux shell or subprocess. Their Android adapters normalize results for the app and use the projects' bundled datasets. Tookie's optional Selenium/ChromeDriver scraper is excluded because this app does not ship a compatible browser driver. GitHub OSINT uses GitHub's REST API directly.

The bundled Blackbird core is GPLv3; the source tree includes its license, upstream notices, and vendored corresponding source. Sherlock and Tookie-OSINT are MIT-licensed. WhatsMyName data is CC BY-SA 4.0.

## Native-only UI rule

The current UI does not expose Google dorks, PhoneInfoga/Phonia/IntelBase launchers, WhatsApp/Telegram links, or third-party reverse-lookup buttons. Unsupported providers are not represented by fake cards or simulated results. Google Gaia IDs, service activity, owner names, locations, Maps reviews, and Play Games profiles are not synthesized from an email hash; only values actually returned by a public endpoint may be shown. If a native request is blocked, times out, rate-limited, or has no supported API, the app shows an error, an unreachable count, or an empty result with an explanation.

Result cards may still offer an optional link to the exact public profile or source record that was found. That link is evidence follow-up, not a second search and not a claim of verified ownership.

Phonia-style WhatsApp, Telegram, Viber, and Truecaller shortcuts are not native public APIs and are not represented as successful checks. IntelBase has no supported public API in this project, so it is not queried or simulated.

Who’s Ringing UK is included in the indexed web query and as an optional manual lookup link. Its public pages returned HTTP 403 to the app-style automated request during development, so the app does not scrape or report it as an automatically checked reputation source.

## Estimates

Country, area, time zone, and line type are derived from public numbering metadata and prefixes. They are not live carrier verification and do not prove where a caller is located.

## Interpretation rule

Treat an exact-number report from a named source as evidence about that source's user reports only. Treat no result as "not found by the checked source," not as proof that the number is safe or unassociated with a person.
