# Third-party scan engines and data

This application bundles Python scan code from the upstream projects below. Android adapters in `app/src/main/python/upstream_osint.py` call each project's scan logic and convert results for the app UI. The app-specific integration is adapted for Android and does not launch the upstream terminal interface.

## Sherlock

- Project: https://github.com/sherlock-project/sherlock
- Vendored commit: `e40a45ec2a074b90703b3b4b842c8a3adbd6ada3`
- Source: `app/src/main/python/vendor/sherlock_project/`
- License: MIT; license text is included in that directory.
- Android adaptation: pandas is optional and only required by Sherlock's desktop export path; the in-app scanner does not use those exports.

## Blackbird

- Project: https://github.com/antoniaci/blackbird
- Vendored commit: `c8329b2e83f20e3f74b218e68200389d838a6832`
- Source: `app/src/main/python/vendor/blackbird/`
- License: GNU General Public License v3.0; text is included as `app/src/main/python/vendor/blackbird/LICENSE` and at the repository root.
- Android adaptation: the upstream username/email verification functions are called directly with a non-interactive console and local dataset paths. Optional terminal exports, AI features, and file-based command-line flows are not invoked.

## Tookie-OSINT

- Project: https://github.com/Alfredredbird/tookie-osint
- Vendored commit: `ba947ff93ea73b5425a0ee0e7e7a40c1011497f9`
- Source: `app/src/main/python/vendor/tookie_osint/`
- License: MIT; license text is included in that directory.
- Android adaptation: Tookie's normal `scan_site` username scanner is invoked in-process. Import-time SIGINT registration is guarded for worker-thread use. Selenium/ChromeDriver web scraping is optional in the upstream project and is not included as an app feature because this app does not ship a compatible browser driver.

## Holehe

- Project: https://github.com/megadose/holehe
- Vendored commit: `14da70f588538936b20d238783c5e28a0772a2b3`
- Source: `app/src/main/python/vendor/holehe/`
- License: GNU General Public License v3.0; text is included as `app/src/main/python/vendor/holehe/LICENSE.md` and at the repository root.
- Android adaptation: the in-process async site modules are called with concurrency limited to eight requests. CLI update checks and terminal output are not used. The adapter retains only service name/domain/existence status and drops any recovery email, phone number, or other returned metadata. Holehe-only results are labeled unverified unless the app's independent native probes also confirm them.

## WhatsMyName data

The bundled `wmn-data.json` and Blackbird's WhatsMyName integration use data from https://github.com/WebBreacher/WhatsMyName. Blackbird's upstream documentation identifies WhatsMyName data as licensed under Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0). Preserve the upstream attribution and license when redistributing or updating that dataset.

## Coil

- Project: https://github.com/coil-kt/coil
- Dependency: `io.coil-kt:coil-compose:2.7.0`
- License: Apache License 2.0.
- Use: asynchronously renders public avatar URLs in the email-intelligence dashboard; the app does not upload image data to Coil.
