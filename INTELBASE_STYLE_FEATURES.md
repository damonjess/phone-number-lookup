# Independent IntelBase-style features

The email scanner now includes several features inspired by the public IntelBase feature description, implemented independently without calling IntelBase.

## Included

- Public DNS-over-HTTPS checks for MX, SPF, and DMARC.
- Mail-acceptance indication based on MX records.
- MX provider hint.
- Free-provider, disposable-like, and role-mailbox indicators.
- An explained local email-quality assessment from 0 to 100.
- XposedOrNot breach names and a timestamped local evidence timeline.
- Public-signal correlation hints based on the app's own username results.
- Source-based profile suggestions for manual review.

## Limitations

The local score is not IntelBase's score and is not an identity or safety decision. DNS records show domain configuration, not ownership of a mailbox. A breach match does not prove current compromise, and no breach result does not prove safety.

The app does not retrieve passwords, infostealer credentials, private account data, or hidden platform records. It does not scrape IntelBase or bypass service access controls.
