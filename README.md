
## Building in Android Studio

Open the project in Android Studio and let Gradle sync before running the app. If Gradle reports `SDK location not found`, install the Android SDK through Android Studio's SDK Manager and select the installed SDK location under **File → Project Structure → SDK Location**. Do not commit `local.properties`; it contains a machine-specific path. The `HistoryStore` includes a JVM-test fallback so unit tests do not call Android's unmocked `getSharedPreferences()` method.
