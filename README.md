# Phone Number Lookup & OSINT Suite

An Android application built with **Kotlin** and **Jetpack Compose** that provides a comprehensive **Open Source Intelligence (OSINT)** toolkit for investigating phone numbers, usernames, and email addresses.

---

## 📱 What the App Does

**Phone Number Lookup & OSINT Suite** combines multiple intelligence frameworks and public search engines into a single mobile interface. It helps users analyze unknown phone numbers, perform reverse lookups, investigate email/username digital footprints, and protect against spam or scams.

---

## 🎯 What It's Used For

* **Caller & Scam Identification:** Analyze unknown callers to identify line types (mobile, landline, VoIP, toll-free), geographic area codes, and potential spam ratings.
* **Phone Number Normalization & Analysis:** Convert raw phone numbers into standard E.164, national, international, and RFC3966 formats while identifying the country, time zone, and region.
* **Social Media & Web Footprinting:** Locate social media accounts, public profiles, messaging handles, and forum footprints linked to a specific phone number, username, or email.
* **Data Breach & Leak Auditing:** Search public paste sites, document dumps, and breach indexes (e.g., HaveIBeenPwned, Epieos, Google Dorks) for exposed contact details.

---

## ✨ Key Features

### 1. 📞 Phone Lookup Engine
* **Number Breakdown & Formatting:**
  * E.164, International, National, and RFC3966 `tel:` links.
  * Automatic UK prefix normalization (`0...` to `+44...`).
  * Country detection, line/network classification (Mobile, Landline, Freephone, Non-Geographic, VoIP), and time zone mapping.
  * UK geographic area code identification (e.g., London, Manchester, Birmingham, Oxford, Stoke-on-Trent, etc.).
* **PhoneInfoga OSINT Integration:**
  * **Google Dorks:** Custom target queries for Social Media (Facebook, LinkedIn, Instagram, X/Twitter, TikTok), Pastebins (Pastebin, Ghostbin, GitHub, GitLab), Documents (PDF, Word, Excel), Spam DBs (WhoCalled, Tellws, ShouldICall), and Business Records (UK Companies House, Gov.uk).
  * **External Reverse Lookups:** Quick links to NumLookup, SpyDialer, Sync.ME, and WhoCalls.me.
* **Phonia OSINT Engine:**
  * Direct profile triggers for **WhatsApp** (`wa.me`), **Telegram** (`t.me`), **Viber**, and web queries for **Truecaller**.
* **IntelBase OSINT Portal:**
  * Direct query integration with the IntelBase intelligence hub.
* **Live DuckDuckGo Web Search:**
  * Real-time web search for direct online mentions of the analyzed phone number.

### 2. 👤 Username & Email OSINT Suite
* **Blackbird Username Scanner:**
  * Instantly checks username availability and footprint across **15+ major platforms** (GitHub, X/Twitter, Instagram, Reddit, TikTok, LinkedIn, YouTube, Pinterest, Twitch, Telegram, Spotify, Steam, Medium, GitLab, Keybase).
* **Tookie-OSINT & Brib Target Scanner:**
  * Alias footprinting across forum archives, breach logs, paste sites, and cryptographic key mappings.
* **Blackbird Email OSINT:**
  * Breach and leak searches, HaveIBeenPwned integration dorks, and reverse lookup via Epieos and IntelBase.

---

## 🛠️ Tech Stack & Architecture

* **Language:** Kotlin
* **UI Framework:** Jetpack Compose with Material 3 Design
* **Concurrency:** Kotlin Coroutines (`Dispatchers.IO`)
* **Networking:** Lightweight HTTP connections (`HttpURLConnection`) for privacy-focused web searches
* **Architecture:** Component Activity with declarative Compose state management
* **Target SDK:** Android SDK 34+ (Compatible with Android 7.0 / SDK 24+)

---

## 🚀 Getting Started

### Prerequisites
* **Android Studio** (Jellyfish / Koala or newer recommended)
* **JDK 17** or higher
* Android device or emulator running **Android 7.0 (API 24)** or higher

### Building & Running
1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/phone-number-lookup.git
   cd phone-number-lookup
   ```
2. Open the project in **Android Studio**.
3. Sync Gradle project files.
4. Run on an emulator or connected Android device:
   ```bash
   ./gradlew assembleDebug
   ```

---

## ⚠️ Disclaimer

This tool is designed strictly for **legitimate security research, spam identification, and personal privacy auditing**. All information returned by this application is derived from publicly available web sources, official APIs, and search engines. Please respect local laws and platform terms of service when conducting OSINT investigations.
