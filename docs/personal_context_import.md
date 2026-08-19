# Personal Context place intake

status: implementation-prepared
last_updated: 2026-08-19

ChikaBell accepts a dedicated, versioned Android Intent from the Personal Context app so a place-related Task can enter the existing ChikaBell shared-place review flow.

This is an intake boundary only. Receiving the Intent must not silently insert a Location or register a geofence; the existing preview / human confirmation path remains authoritative.

## Contract

Package: `com.chikabell.app`

Action: `com.chikabell.app.action.IMPORT_PLACE`

Data URI: `chikabell://import-place/<dedupe-key>`

Required extras:

- `com.chikabell.app.extra.CONTRACT_VERSION` = `1`
- `com.chikabell.app.extra.SOURCE` = `personal-context`
- `com.chikabell.app.extra.DEDUPE_KEY`

Optional/conditional extras:

- `SOURCE_TASK_ID`
- `SOURCE_PLACE_ID`
- `NAME`
- `LATITUDE` + `LONGITUDE` as a pair
- `ADDRESS`
- `NOTE`

The receiver rejects unsupported versions, source mismatch, missing/mismatched dedupe identity, partial coordinate pairs, or out-of-range coordinates. At least a name, address, or coordinate pair must remain usable.

The validated payload is normalized into `SharedPlaceParser` with a `personal-context:<dedupe-key>` lineage so the existing ChikaBell registration-review pipeline remains in control.

## Privacy

The contract uses a package-targeted Android Intent. Place fields stay in extras rather than URI query parameters. No new server/API is introduced.

## Acceptance before merge

Run the existing Android tests plus the new `SharedIntentNormalizerTest` cases, build the debug APK, and confirm on-device that a valid Personal Context Intent opens the normal registration preview without auto-registering a location.
