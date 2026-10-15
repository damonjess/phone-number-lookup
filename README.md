# Username + email search: accuracy and coverage fixes

Copy into the same paths in your project (package `com.example.phone_number_lookup`).

New files
- `SitePriority.kt`: popular sites scanned first
- `UsernameVariants.kt`: john.smith -> johnsmith, john_smith, ...
- `HitVerifier.kt`: control check that removes "answers yes to everything" sites
- `SiteKey.kt`: merges the same profile page arriving from different site lists (and `SiteKeyTest.kt`)
- tests: SiteKeyTest, SitePriorityTest, UsernameVariantsTest, HitVerifierTest, ScanDepthTest

Replaced files (small edits to your originals, except UsernameScanner which is rewritten with the same public API)
- `UsernameScanner.kt`, `EmailRegistrationScanner.kt`, `OsintDatasets.kt`, `ScanHttp.kt`, `EmailScanner.kt`
- `ui/viewmodels/UsernameAndEmailOsintViewModel.kt`, `ui/screens/UsernameAndEmailOsintScreen.kt`

The UI still counts hits by the exact strings "Matched" and "Registered response", which are unchanged.

Also updated: `LiveEndpointSmokeTest.kt` (Tookie now contributes only its genuinely new sites after de-duplication, so the threshold changed from > 50 to >= 10).
