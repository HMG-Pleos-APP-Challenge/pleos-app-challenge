> 2026-11-11 통합 상태와 변경 사항은 [통합 검토 문서](../docs/integration-review.md)가 우선합니다. 아래는 단계별 구현 기록입니다.

# Supabase backend v1

## Apply to qcbesfuohbvprpkwmlqu

1. Enable Anonymous Sign-Ins in Authentication. No administrator/secret key belongs in Android.
2. Fresh projects: run `sql-editor/00-schema.sql`, `01-catalog.sql`, `02-catalog.sql` separately in order. Replace the editor content each time. The final catalog batch activates grid500 after checking all 243 cells exist. The former combined setup-first-install.sql is retired due to SQL Editor size limits.
   Existing installed projects: run `grid500-upgrade/00-upgrade.sql`, then `01-catalog.sql`, then `02-catalog.sql`. Do not rerun the fresh-install schema. Each upgrade is retryable; existing targets/grants remain preserved. The final step groups old grants by floor(col/5), floor(row/5), retains the earliest achievement, awards 10 XP per merged cell and switches the active catalog atomically.
   Regenerate with `python tools/export_supabase_catalog.py`, then `python tools/prepare_supabase_editor.py`; copy the migration 003 and the generated catalog batches into the existing-project upgrade bundle when releasing a new catalog.
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

PostGIS uses SRID 0 local metres with exactly the app projection (origin 126.8,37.2). Never interpret these shapes as EPSG:4326. Catalog source attribution and licences remain in app asset documentation; preserve them when exporting/sharing the DB. Export seed using python tools/export_supabase_catalog.py; 243 cells (500m) + 1 region + 1 landmark.

## Collaborator handoff

Protected files changed: app/build.gradle.kts (public build config and test-only Robolectric), AndroidManifest.xml (INTERNET). No core models/navigation/home/conquest screen changes.
Conquest tables are reserved and RLS-locked with no client read/write API; real shared road identifiers and ownership transfer rules need collaborator agreement. Cells are never treated as conquest road IDs.
Notion contract could not be fetched (404); shared approval is not marked complete. No push/PR/merge is implied.

Security: all user tables have RLS; only own profile/grants are readable. Grant/candidate writes are via authenticated SECURITY DEFINER RPC, explicit grants and empty search_path. RPC locks the user cursor, validates accuracy/region/road/dwell/movement and awards once through a composite primary key. It cannot establish physical driving or defeat a malicious GPS client without trusted signals.

## Verification

Android: JDK17, gradlew testDebugUnitTest assembleDebug lintDebug. 86 tests passed including 5 Android 28 SQLite outbox tests and 3 grid migration tests.
Backend: cd supabase && npm ci && npm test (Node 22+). Isolated WASM PostgreSQL/PostGIS tests stub Supabase auth.uid()/roles locally, never touch cloud data. Tests exercise 245 active catalog targets, spatial grant, XP idempotency after lost response, real/emulator isolation, null-accuracy rejection, direct-write denial, RLS cross-user privacy and unauthenticated RPC denial. Hosted GoTrue integration is separate and requires Anonymous Sign-Ins enabled.

2026-11-10 hosted verification: anonymous signup succeeded; sync_exploration_v1 with an empty emulator batch returned XP=0 and no grants; exact catalog counts were region=1, cell=1579, landmark=1; explorer profile was created and read back. The configured debug APK was installed with adb install -r and MainActivity launched on emulator-5554/user 10 without AndroidRuntime errors in the checked log. Emulator GPS was not changed and tracking was not automatically started. End-to-end app-originated upload remains for manual Start exploration verification; API checks do not imply this UI flow has been exercised.

## 500m grid update (2026-11-10)

- Same 643 source ways / 612 clipped road paths, new grid500-v2 catalog: 243 eligible cells instead of 1579. One cell is about 0.41% of Namyang progress. Region/road-distance/accuracy/3-second dwell/8m movement conditions remain. A cell unlock does not mean every road in that square was driven.
- All 1579 old cell indices map to an eligible 500m cell (checked against both catalogs). Local startup migrates all three partitions before reading the new catalog, keeps `*-cells-grid100-v1.backup.bin`, merges by user + new cell and keeps the earliest timestamp. Unknown/corrupt versions stop without replacing the file.
- Local XP uses a new `*-grid500-xp.bin` ledger rebuilt from merged achievements; old XP files remain. Counts/XP can decrease when old cells merge. Server XP similarly counts only the active catalog. Existing 100m grants stay stored as historical records and are excluded from active totals.
- RPC includes catalog_version. A 500m app will not acknowledge queued samples using a 100m server response or restore an old-version cached snapshot. This prevents mixed denominators while remote SQL is pending.
- Local validation: Android tests 86/86, build/lint passed; PostGIS catalog, spatial unlock, RLS and grant merge/idempotency passed. Hosted RPC now reports grid500-v2 and exact count 243 after the user applied the three upgrade files. Installed APK startup migrated the device emulator cache from 16 old cells to 9 merged cells and created the original-version backup. GPS was not changed; app-originated upload after manual tracking start is still a separate check.
