# Phone Number Lookup

Android app for checking phone numbers, usernames, and email-related OSINT signals from public data sources.

This project combines offline number parsing with live public-source checks, including phone lookups, username/email registration checks, GitHub identity search, and browser-style result review.

## Features

- Phone number validation and metadata lookup
  - Number normalization and local parsing via libphonenumber
  - Country/region detection and number type classification
  - Public lookup-style checks for phone and caller data signals
- Username + email intelligence
  - Integrated upstream username scanning via embedded Python/Chaquopy cores
  - Email registration checks and public profile discovery
  - Optional breach-report lookups
- GitHub OSINT search
  - Search for users and repositories through the public GitHub API
  - Link out to public profiles and evidence pages
- Android UI
  - Compose-based app UI
  - Results grouped by source, confidence, public links, and interpretation status

## Important caveat

This app is designed for public-source investigation and research workflows. It does not prove real-world identity ownership or guarantee a person is safe or malicious. Results should be interpreted as evidence from a source, not as verified personal identity.

See `OSINT_STATUS.md` for the project’s status notes, interpretation rules, and supported/unsupported checks.

## Tech stack

- Kotlin
- Jetpack Compose
- Android SDK 37
- Chaquopy for bundled Python-based scan cores
- libphonenumber for phone parsing
- Requests / BeautifulSoup / other Python libraries used by the bundled intelligence modules

## Project structure

- `app/` — Android app module
- `app/src/main/java/` — Kotlin app logic and UI
- `app/src/main/python/` — bundled Python tooling used by the app
- `app/src/main/assets/` — assets and bundled data
- `OSINT_STATUS.md` — feature and interpretation status
- `LICENSE` — repository license
- `THIRD_PARTY_NOTICES.md` — bundled dependency notices
- `review.md` — project review notes

## Requirements

- Android Studio or Gradle-based Android build environment
- JDK 11+
- Python 3.12 (used by Chaquopy build setup)
- Android SDK with API 37 target support

## Building

```bash
./gradlew assembleDebug
```

Or open the project in Android Studio and run the app on an emulator or physical device.

## Running tests

```bash
./gradlew test
```

## Notes

- The app intentionally separates live public checks from verified identity claims.
- Some checks depend on external services and may be rate-limited, blocked, or unavailable.
- Several bundled search modules are upstream Python projects adapted for Android in-process, rather than shelling out to a Termux environment.

## License

See `LICENSE`.
