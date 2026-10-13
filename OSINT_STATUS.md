# OSINT feature status

The app contains several different kinds of OSINT features. They are intentionally not all presented as verified identity lookups.

## Live in-app checks

- **PhoneInfoga-compatible native modules:** the local scanner is automatic; Numverify carrier/line data is available through an optional apilayer key entered in the app; public footprint results use the app's native web-search request.
- **Tellows:** the app requests the public caller-reputation response and parses its score, report count, location, and caller type when returned.
- **DuckDuckGo web search:** the app requests a public HTML search and extracts result titles, snippets, and links. Results may be blocked or incomplete.
- **GitHub OSINT:** the app uses GitHub's public REST endpoints for usernames and repositories. Unauthenticated requests are rate-limited.
- **Username scanner:** the app probes the public endpoints described by the merged site datasets. Sites that block, time out, or return ambiguous responses are reported separately where supported.
- **Email breach check:** the app sends the entered email to XposedOrNot and displays the response. A negative response is not proof that the address has never appeared in a breach.

The GitHub-derived tools are represented by their native functions rather than launcher cards. The merged WhatsMyName, Sherlock, and Tookie datasets are used by the in-app username HTTP scanner. Blackbird-style email registration probes are used by the in-app email scanner, while the breach result comes from XposedOrNot. GitHub OSINT uses GitHub's REST API directly.

## Native-only UI rule

The current UI does not expose Google dorks, PhoneInfoga/Phonia/IntelBase launchers, WhatsApp/Telegram links, or third-party reverse-lookup buttons. Unsupported providers are not represented by fake cards or simulated results. If a native request is blocked, times out, rate-limited, or has no supported API, the app shows an error, an unreachable count, or an empty result with an explanation.

Result cards may still offer an optional link to the exact public profile or source record that was found. That link is evidence follow-up, not a second search and not a claim of verified ownership.

Phonia-style WhatsApp, Telegram, Viber, and Truecaller shortcuts are not native public APIs and are not represented as successful checks. IntelBase has no supported public API in this project, so it is not queried or simulated.

## Estimates

Country, area, time zone, and line type are derived from public numbering metadata and prefixes. They are not live carrier verification and do not prove where a caller is located.

## Interpretation rule

Treat an exact-number report from a named source as evidence about that source's user reports only. Treat no result as "not found by the checked source," not as proof that the number is safe or unassociated with a person.
