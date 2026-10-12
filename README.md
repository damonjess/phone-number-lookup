# Phone Number Lookup & OSINT Suite

An Android application built with **Kotlin** and **Jetpack Compose** that provides a comprehensive **Open Source Intelligence (OSINT)** toolkit for investigating phone numbers, usernames, and email addresses.

Everything runs **in the app, on the phone**. There is no Python, no `pip`, no `git clone`, and no Termux — the capabilities of the command-line tools below are ported natively.

---

## 📱 What the App Does

**Phone Number Lookup & OSINT Suite** combines multiple intelligence frameworks and public search engines into a single mobile interface. It analyzes unknown phone numbers, performs reverse lookups, finds accounts registered to a username or email address, and identifies spam callers.

---

## 🎯 What It's Used For

* **Caller & Scam Identification:** Identify line types (mobile, landline, VoIP, toll-free), geographic area codes, spam scores, and what other people reported about a number.
* **Phone Number Normalization & Analysis:** Convert raw phone numbers into standard E.164, national, international, and RFC3966 formats while identifying the country, time zone, and region.
* **Account Discovery:** Find which sites already have an account for a given username or email address.
* **Data Breach & Leak Auditing:** Check an email address against known public breaches.

---

## ✨ Key Features

### 1. 📞 Phone Lookup Engine
* **Number Breakdown & Formatting:** E.164, International, National and RFC3966 `tel:` links; UK prefix normalization (`0...` → `+44...`); country, line/network type and time-zone detection; UK area-code identification.
* **Live Spam Database Scan** — an actual data lookup, not a link:
  * Queries the **tellows** crowd-sourced caller database JSON API and returns the spam score (1–9), community report count, times searched, reported location and country.
* **Community Report Databases** — additional live lookups:
  * **WhoCallsMe** and **800notes** user-report threads, showing how many reports exist and what the number was reported as (debt collector, scam, telemarketing, …).
  * These two only index North American (+1) numbers, so they are queried for `+1` numbers and clearly labelled as not applicable otherwise rather than silently returning nothing.
* **PhoneInfoga / Phonia / IntelBase dorks:** Google dorks for social media, pastebins, documents, business records and spam databases, plus reverse-lookup and messenger profile links (`wa.me`, `t.me`).
* **Live DuckDuckGo Web Search:** Real-time web results mentioning the number.

### 2. 👤 Username & Email OSINT Suite

**Username → accounts.** The scanner downloads the community site databases and performs a real HTTP request against each site's documented check endpoint, then classifies the response. Sites found in more than one database are only probed once.

| Ported from | What it contributes |
|---|---|
| **WhatsMyName** (`wmn-data.json`) | ~694 probe definitions with `e_code` / `e_string` / `m_code` / `m_string` signals |
| **Sherlock** (`data.json`) | ~310 definitions, including `status_code`, `message` and `response_url` detection and POST-based checks (Discord, Anilist, Holopin) |
| **Tookie-OSINT** (`sites.json`) | ~235 additional sites using an append-URL plus an error-message marker |

That merges to **~1,239 probes**, and each one is an actual scan. Scan depth is selectable (Quick 150 / Standard 300 / Full sweep), and confirmed profiles are listed as tappable links tagged with the database they came from.

**Email → accounts.** Each check asks a service's own signup/availability/status endpoint whether the address is already taken — the method the **Holehe** and **Blackbird** modules use.

* **Blackbird email dataset** (`email-data.json`) supplies the probe definitions, including POST bodies, custom headers and per-service response markers.
* **Hand-verified probes** that take precedence over same-named dataset rows, because the live endpoint disagreed with the published dataset:
  * **Mozilla Firefox** — `POST /v1/account/status` → `{"exists":true|false}`.
  * **Chess.com** — `{"isEmailAvailable":false}` means the address is taken (the dataset's HTTP 226 marker no longer exists).
  * **Gravatar** — keyed by an **MD5** of the address; the dataset incorrectly specified SHA-256.
* **Live Breach Check:** queries the free **XposedOrNot** API and lists the actual breaches the address appears in.
* **Handle Footprint:** the part before the `@` is automatically scanned as a username, because that handle is very often reused elsewhere.

### 3. 🐙 GitHub OSINT
* Live queries against the **public GitHub REST API** (no token required): user search, repository search, and full profile drill-down (name, bio, company, location, website, public email, repos, followers, join date).

---

## 🔎 Live Scanners (free, no API key)

These perform **real network requests and return real data** — they do not just open a browser tab:

| Scanner | Source (free, keyless) | What it returns |
|---|---|---|
| Phone spam / caller reputation | `tellows.co.uk` / `tellows.de` JSON API | Spam score 1–9, report count, reported location, country |
| Phone user reports | WhoCallsMe, 800notes | Report count and what callers reported (US/CA numbers only) |
| Phone web mentions | DuckDuckGo HTML search | Live web results mentioning the number |
| Username enumeration | WhatsMyName + Sherlock + Tookie-OSINT | Confirmed profiles across ~1,239 sites via real HTTP probes |
| Email registration | Blackbird email dataset + verified probes | Services that already have an account for the address |
| Email breaches | XposedOrNot `check-email` API | Names of breaches the address appears in |
| GitHub intelligence | `api.github.com` public REST | Users, repositories and full profiles |

The **dork and link buttons** (PhoneInfoga, Phonia, IntelBase, pastebin/document dorks, manual profile links) are labelled as *launchers* — they open a search engine or a third-party page in your browser and do not return data in-app. The UI now distinguishes the two instead of presenting both as scans.

---

## 📊 Accuracy notes (read this)

* **A "no result" is not proof of absence.** Each third-party site decides how it answers, and coverage changes over time.
* **Sites that block automated clients** (HTTP 403/401/5xx, Cloudflare challenges) are counted as *unreachable* and reported separately — never silently guessed at. The UI shows this count next to the results.
* **Known-unreliable checks are excluded.** `OLX` (Cloudflare 403, intermittently a misleading 200) and `Imageshack` (a captcha error code that collides with the dataset's markers) are skipped rather than allowed to produce false "account registered" reports. `spamcalls.net` was dropped from phone reputation after returning HTTP 410 for every number tested.
* **A tellows score of 5 with zero community reports is not a warning.** That is tellows' placeholder value when nobody has submitted anything, so the app reports the number as *unrated* and marks the score as a default rather than banding it as "suspicious". A score only becomes a verdict once community reports exist.
* **A missing signal always wins over a found signal.** This ordering is what keeps JavaScript/SPA sites, which return the same page to everyone, from producing false positives.
* **Username scanning is as reliable as each target site.** Expect some false negatives from sites that block bots.

---

## ✅ Verification

* **41 unit tests pass**, including offline parsing tests for every dataset format, detection tests that drive the real request path against a local HTTP server (found, missing, body-signal, POST-body and redirect cases), and scan-coordination tests covering overlapping scans.
* **Overlapping scans cannot corrupt each other.** Starting a new scan cancels the previous one and retires its token, so a slow sweep finishing last cannot replace a newer scan's results. Verified on device: a full 1,239-site sweep for `blue` started, then overtaken by a 150-site `qzwx` scan, now correctly leaves qzwx's own results on screen — previously it displayed 563 profiles belonging to blue under the qzwx heading.
* **Live smoke tests** (skipped automatically when offline) assert that the datasets actually download and that a real account is found while a made-up one is not:
  * Merged username database: **1,239 probes** (WhatsMyName 694, Sherlock 310, Tookie 235), with no duplicates after the merge.
  * `blue` (Sherlock's own example account) → 27 profiles; a random handle → 4. A random handle never out-scores a real one.
  * **Gravatar, two-sided:** a real public address → `FOUND` (HTTP 200); a made-up address → `NOT_FOUND`. Against every probe, a made-up address produces **zero** false registrations.
* `./gradlew :app:testDebugUnitTest :app:assembleDebug` completes successfully and produces `app/build/outputs/apk/debug/app-debug.apk`.

---

## 🛠️ Tech Stack & Architecture

* **Language:** Kotlin
* **UI Framework:** Jetpack Compose with Material 3
* **Concurrency:** Kotlin Coroutines (`Dispatchers.IO`, `async` + `Semaphore` for bounded parallel scans)
* **Architecture:** MVVM — `ui/viewmodels/` holds one ViewModel per tab so inputs and results survive rotation and configuration changes, `ui/screens/` holds the tab composables, and `ui/components/` holds shared widgets
* **Networking:** Dependency-free HTTP (`HttpURLConnection`, GET and POST) and Android's built-in `org.json` — no third-party SDKs, no API keys, no accounts
* **Datasets:** downloaded at runtime from GitHub raw URLs and cached in memory ([`OsintDatasets.kt`](app/src/main/java/com/example/phone_number_lookup/OsintDatasets.kt)), so site coverage updates without an app release
* **Target SDK:** `compileSdk`/`targetSdk` 37, `minSdk` 26 (Android 8.0+)

### Where the code lives

| File | Responsibility |
|---|---|
| [`ScanHttp.kt`](app/src/main/java/com/example/phone_number_lookup/ScanHttp.kt) | Minimal GET/POST HTTP client, records the final redirected URL |
| [`OsintDatasets.kt`](app/src/main/java/com/example/phone_number_lookup/OsintDatasets.kt) | Downloads, parses, merges and de-duplicates every dataset; classifies probe responses |
| [`CuratedEmailChecks.kt`](app/src/main/java/com/example/phone_number_lookup/CuratedEmailChecks.kt) | Hand-verified email probes that override stale dataset rows |
| [`UsernameScanner.kt`](app/src/main/java/com/example/phone_number_lookup/UsernameScanner.kt) | Parallel username enumeration with progress reporting |
| [`EmailRegistrationScanner.kt`](app/src/main/java/com/example/phone_number_lookup/EmailRegistrationScanner.kt) | Email → registered-account discovery and handle extraction |
| [`SpamScanner.kt`](app/src/main/java/com/example/phone_number_lookup/SpamScanner.kt) | tellows caller-reputation lookup |
| [`PhoneReputation.kt`](app/src/main/java/com/example/phone_number_lookup/PhoneReputation.kt) | WhoCallsMe and 800notes report parsing |
| [`GitHubOsint.kt`](app/src/main/java/com/example/phone_number_lookup/GitHubOsint.kt) | GitHub public REST API queries |
| [`ui/viewmodels/ScanCoordinator.kt`](app/src/main/java/com/example/phone_number_lookup/ui/viewmodels/ScanCoordinator.kt) | Cancels superseded scans and stamps results so stale ones are discarded |
| [`ui/screens/`](app/src/main/java/com/example/phone_number_lookup/ui/screens) | One composable screen per tab |
| [`ui/viewmodels/`](app/src/main/java/com/example/phone_number_lookup/ui/viewmodels) | Scan state that survives rotation |

---

## 🚀 Getting Started

### Prerequisites
* **Android Studio**
* **JDK 17** or higher
* An Android device or emulator running **Android 8.0 (API 26)** or higher

### Building & Running
1. Open the project in **Android Studio**.
2. Sync Gradle project files.
3. Build and install:
   ```bash
   ./gradlew assembleDebug
   ```
4. Install `app/build/outputs/apk/debug/app-debug.apk` on your phone.

The app needs an internet connection; all sources are public and require no API keys or accounts.

---

## ⚠️ Disclaimer

This tool is for **legitimate security research, spam identification, and personal privacy auditing**. All information returned is derived from publicly available web sources and public APIs. Respect local laws and platform terms of service when conducting OSINT investigations.
