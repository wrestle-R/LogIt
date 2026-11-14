# Implementation notes

## Compatibility

Both clients retain the existing `habits` documents, `userId`, `completions` date strings, `order`, and streak fields. The timezone remains Asia/Kolkata to match the original client. `dailyCounts` adds a per-date count; reads take the maximum of that count and the legacy completed-date baseline of one. Existing accounts use the same Firebase project and Google identity.

New logs and Undo run in Firestore transactions. They require a connection; failures remain visible and do not claim a successful save. Undo targets the original log date. New clients deduplicate completed days. The original deployed client still only shows a binary completion and cannot display multiple logs. Its array-overwrite writes can still race with legacy writes, but the additional daily counts are preserved. Updating the upstream client would be needed to eliminate its old write behaviour entirely.

No Firestore rules were loosened or deployed. Existing authenticated reads and log/Undo transactions were exercised on the connected phone. This is functional evidence, not a security audit of the shared project.

## Android

Package: `com.romeiro.logit`. Widget surfaces support a single activity or up to four chosen activities, ten preset accents, preset gradients, 4/8/16/26/52 week histories, dark/light surfaces, app-opening taps and confirmation before logging. Accounts are associated with widget configurations to avoid displaying a previous account’s cached activity after sign-out. Widgets refresh after local activity changes, resize callbacks and periodic launcher updates; Android controls background update scheduling.

The included debug build is for local testing. A production release needs an appropriate release signing key and its registered certificate fingerprints. No private Admin SDK key is included in source or APK.

## Verification record

- Next production build and TypeScript passed before the latest tooltip additions; final rerun pending.
- Four web model checks passed: legacy counts, repeated logs/Undo, legacy overwrite resilience and IST midnight boundary.
- Three Android model checks passed.
- Native Google sign-in loaded the existing account’s activity on the physical Nothing A001, Android 16.
- A live Firestore log succeeded and Undo returned to the activity; verify the final displayed count in the current build.
- Native dialog and spacing revised after owner feedback; Move button removed, drag handle retained.
- Responsive heatmap checked at 375px: no horizontal overflow, 3px vertical cell gaps after overlap fix. More sizes and launcher widget checks in progress.
