# Android Studio update

## Easiest option: open the updated project

1. Download and extract `phone-number-lookup-updated.zip`.
2. In Android Studio, choose **File → Open** and select the extracted `phone-number-lookup` project folder (the folder containing `settings.gradle.kts`).
3. Allow Gradle sync to finish, then run the app on an emulator or device.

The project uses the Android Gradle Plugin and an Android SDK level specified in `app/build.gradle.kts`. Install the SDK version requested by Android Studio's sync prompt if it is not already installed.

## Apply just these changes to an existing checkout

Copy these files into the same relative paths in your project:

- `app/src/main/java/com/example/phone_number_lookup/SearchRepository.kt`
- `app/src/main/java/com/example/phone_number_lookup/OsintTools.kt`
- `app/src/main/java/com/example/phone_number_lookup/ui/viewmodels/PhoneLookupViewModel.kt`
- `app/src/main/java/com/example/phone_number_lookup/ui/screens/PhoneLookupScreen.kt`
- `app/src/test/java/com/example/phone_number_lookup/PhoneSearchQueryTest.kt` (new test)

## What changed

A phone scan now makes up to three DuckDuckGo HTML searches: exact number spellings; the number with spam/scam/caller-report terms; and the number restricted to indexed results from Tellows, Who Called UK, Should I Answer, WhoCallsMe, and Who’s Ringing. Duplicate result URLs are merged, with up to 30 results returned across these searches. Partial search failures remain visible rather than being treated as a complete empty result. Existing caller-reputation checks, numbering metadata, and risk assessment remain in place. For UK numbers, a separate manual link opens the matching Who’s Ringing page because the site rejects automated requests; it is not represented as an automatically scanned source.

These are public, indexed web results—not comprehensive database access. The app does not query private sign-up forms or discover private account registrations. Online checks transmit the entered number to DuckDuckGo and existing reputation sources; numbers can be spoofed, and a numbering-area estimate cannot reveal a caller's live location or prove their identity.
