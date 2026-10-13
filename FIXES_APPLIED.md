# Fixes applied

This version keeps the existing app features and fixes the highest-impact accuracy and reliability issues:

- Added Google's `libphonenumber` dependency for international/UK number parsing.
- Correctly handles `01782...`, `0044...`, `+44...`, and international input formats.
- Corrected Germany, Spain, and Italy time-zone labels.
- Renamed line type output to `Estimated number type` and added a disclaimer that prefix data does not prove the current carrier or caller location.
- Fixed the `tellws.co.uk` typo to `tellows.co.uk`.
- Made external lookup descriptions clear that coverage and identity results are not guaranteed.
- Changed web search to distinguish `no matches` from timeout, HTTP failure, or blocking.
- Reduced the default username scan limit from 300 to 100 for safer personal use.
- Kept the existing scan-cancellation and stale-result protection.

The project still needs Android Studio with Android SDK 37 to create an APK. No provider API keys were added.

## Second improvement build

- Searches the E.164, national, international, and RFC3966 representations together.
- Caches successful web and tellows results for 24 hours to reduce repeated requests.
- Displays an evidence-confidence label for caller reports.
- Lowers username scan concurrency from 16 to 6 to reduce rate limiting and network load.

## Personal history build

- Adds local-only scan history using Android preferences; no new permissions or uploads.
- Saves the normalized number, area/country label, timestamp, and an optional personal note.
- Keeps the latest 50 entries and provides a clear-all control.
- Adds per-entry delete, status cycling (Unknown / Suspected spam / Trusted), note editing, rescan, and clear-all confirmation.

## Native-only OSINT UI build

- Removed PhoneInfoga, Phonia, IntelBase, Google-dork, manual-profile, and third-party lookup launcher sections from the visible UI.
- Phone, username, email, and GitHub tabs now show only results returned by native in-app HTTP/API checks.
- Unsupported sources are not replaced with fake placeholders; the app reports unavailable, blocked, or empty results honestly.

## Native PhoneInfoga and GitHub-tool integration

- Added a native PhoneInfoga-compatible local scanner section.
- Added an optional in-app Numverify/apilayer request for carrier and line-type data; the API key is entered into a masked field and is not hard-coded.
- The existing native username scanner uses the merged WhatsMyName, Sherlock, and Tookie datasets.
- The existing native email scanner uses Blackbird-style registration probes plus the XposedOrNot breach API.
- GitHub OSINT remains a direct GitHub REST API integration.
- Phonia shortcuts and IntelBase launcher behavior remain excluded because no supported public API is available for an honest native result.

## Username and email scanner evidence improvements

- Added explicit evidence text, HTTP status, final redirect URL, confidence, and verification status to positive results.
- 404 responses are labelled as `Not found`; network errors, 403/401 responses, rate limits, and 5xx responses are labelled as unavailable or uncertain rather than as negative matches.
- Added a per-result `Recheck result` action for username and email findings.
- Rechecks keep the result visible and update it to `Matched`, `Not found`, or `Unavailable / uncertain` instead of silently deleting it.
- Result totals now distinguish confirmed matches from retained recheck results.

## Independent IntelBase-style email features

- Added public DNS-over-HTTPS checks for MX, SPF, and DMARC records.
- Added free-provider, disposable-like, and role-mailbox indicators.
- Added an explained local email-quality assessment; it is not presented as an IntelBase score.
- Added a transparent breach timeline using the XposedOrNot lookup time and returned breach names only; no breach dates or leaked secrets are invented or displayed.
- Added public-signal correlation hints and source-based profile suggestions from the app's own username results.
- These features do not call IntelBase, require an IntelBase account, or use an IntelBase API key.
