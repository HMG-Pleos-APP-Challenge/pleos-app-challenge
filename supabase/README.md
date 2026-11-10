# Supabase backend v1

## Apply to qcbesfuohbvprpkwmlqu

1. Enable Anonymous Sign-Ins in Authentication. No administrator/secret key belongs in Android.
2. For a fresh project, open setup-first-install.sql locally, copy its full contents into SQL Editor, then Run. This installs both migrations and the catalog in one transaction. Do not rerun this first-install bundle on an already configured DB. For later changes use ordered migrations.
3. Add to git-ignored local.properties:
   supabase.url=https://qcbesfuohbvprpkwmlqu.supabase.co
   supabase.publishableKey=<publishable key or legacy anon key>
4. Rebuild APK. Start exploration normally; no scripted GPS injection is needed.
5. Map shows sync state, confirmed cell count/XP, pending sample count. Flight mode must preserve pending rows; reconnect retries every 15 seconds while tracking runs.

## Ownership and provisional records

- Supabase Auth UUID is authoritative. Existing installation UUID stays the local cache key; requests never choose a server user_id.
- Anonymous accounts persist encrypted tokens in Android Keystore-backed preferences. Reinstall/device change cannot recover an anonymous account. Account linking/login UI is a follow-up, not implemented.
- Existing binary snapshots remain as local/provisional cache; old unlocks are NOT uploaded as verified achievements. New samples go through server verification.
- Server-confirmed XP takes precedence after successful sync; local map can include provisional records. Production conquest/ranking must use server grants exclusively.
- Mock replay never uploads. pleos-emulator and pleos have independent grant/XP namespaces. Partition labels alone are not device attestation or proof against location spoofing.
- The server stores rolling candidates + latest location, not full route history. Candidates/cursors persist until user deletion or future retention cleanup; define that cleanup before public launch.
- Offline queue persists SQLite rows and a cached server snapshot. At most 10000 unacknowledged samples are accepted; full queue preserves old samples and reports failure rather than silently overwriting them. Server accepts a 30-day offline window. Older samples require explicit recovery rather than resetting an account.
- Sync runs during tracking. Stop/restart or process death keeps rows; background periodic WorkManager sync when not tracking is not implemented.

## Backend boundary and migration

ExplorationCloud is a provider-independent sync interface; replace SupabaseCloud with a future HTTP adapter while retaining local caches and RPC payload contract. Plain SQL migrations and deterministic seed support pg_dump/pg_restore into PostgreSQL + PostGIS. Supabase Auth JWT verification/user migration is a separate migration concern.

PostGIS uses SRID 0 local metres with exactly the app projection (origin 126.8,37.2). Never interpret these shapes as EPSG:4326. Catalog source attribution and licences remain in app asset documentation; preserve them when exporting/sharing the DB. Export seed using python tools/export_supabase_catalog.py; 1579 cells + 1 region + 1 landmark.

## Collaborator handoff

Protected files changed: app/build.gradle.kts (public build config and test-only Robolectric), AndroidManifest.xml (INTERNET). No core models/navigation/home/conquest screen changes.
Conquest tables are reserved and RLS-locked with no client read/write API; real shared road identifiers and ownership transfer rules need collaborator agreement. Cells are never treated as conquest road IDs.
Notion contract could not be fetched (404); shared approval is not marked complete. No push/PR/merge is implied.

Security: all user tables have RLS; only own profile/grants are readable. Grant/candidate writes are via authenticated SECURITY DEFINER RPC, explicit grants and empty search_path. RPC locks the user cursor, validates accuracy/region/road/dwell/movement and awards once through a composite primary key. It cannot establish physical driving or defeat a malicious GPS client without trusted signals.

## Verification

Android: JDK17, gradlew testDebugUnitTest assembleDebug lintDebug. 82 tests passed including 4 Android 28 SQLite outbox tests.
Backend: cd supabase && npm ci && npm test (Node 22+). Isolated WASM PostgreSQL/PostGIS tests stub Supabase auth.uid()/roles locally, never touch cloud data. Tests exercise 1581 catalog targets, spatial grant, XP idempotency after lost response, real/emulator isolation, null-accuracy rejection, direct-write denial, RLS cross-user privacy and unauthenticated RPC denial. Hosted GoTrue integration is separate and requires Anonymous Sign-Ins enabled.

Hosted API connectivity and anonymous signup succeeded. Remote RPC returned PGRST202 (sync_exploration_v1 not installed); remote DDL is still pending the dashboard SQL Editor step. Local validation does not imply deployed backend success.
