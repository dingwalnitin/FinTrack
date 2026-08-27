# Stage 11 Report — P23 Import/Export Core + P24 Privacy/Security

## 1. Files/classes/components created or changed

### Domain

- `domain/model/Backup.kt` — versioned manifest, dataset vocabulary, validation, preview, conflict and merge result models. Provider secrets have no dataset representation.
- `domain/model/SettingsProfile.kt` — separate settings-profile model, privacy posture and audit retention vocabulary.
- `domain/service/BackupService.kt` — deterministic export, validation-before-staging, conflict preview and explicit merge orchestration.
- `domain/service/BackupCodec.kt` — deterministic line-oriented `FTBACKUP1` envelope and per-dataset checksums.
- `domain/service/BackupCrypto.kt` — AES-256-GCM encrypted export envelope with PBKDF2-HMAC-SHA256 password derivation and safe failure handling.
- `domain/service/CsvInteropEngine.kt` — declared date/sign mappings, Indian digit grouping, quoted CSV parsing and per-row errors.
- `domain/service/ExportRedactionEngine.kt` — export-safe redaction plus golden fixtures.
- `domain/service/LlmMinimization.kt` — allow-listed LLM payload transform with masked VPA/account fragments and no raw SMS text.
- `domain/service/PrivacySecurity.kt` — settings profile, audit log and app-lock persistence contracts/services.
- `domain/service/AppLockService.kt` — offline lock lifecycle with grace window and fail-closed verification.

### Data/security/UI/wiring

- `data/db/EntitiesV11.kt` — import staging/batch tables, settings profiles, retention-bounded audit log, singleton app-lock state.
- `data/db/migration/MigrationsV11.kt` — additive `10 -> 11` migration.
- `data/db/migration/Migrations.kt` — registered `MIGRATION_10_11`.
- `data/db/FinTrackDatabaseV2.kt` — registered v11 entities/DAO and bumped schema version to 11.
- `data/db/FinanceDaoV9.kt` — export reads, staging, conflict lookups, transaction-scoped commit primitives and P24 persistence.
- `data/repository/RoomBackupRepository.kt` — Room backup sink and canonical row serialization.
- `data/repository/RoomPrivacyRepositories.kt` — Room settings/audit/app-lock sinks.
- `security/KeystoreSecretVault.kt` — Android Keystore-wrapped app-lock secret storage.
- `application/backup/BackupViewModel.kt` — validate/preview/confirm/cancel restore workflow.
- `ui/backup/BackupRestoreScreen.kt` and `ui/backup/ImportPreviewDialog.kt` — offline backup/restore and explicit conflict confirmation UI.
- `ui/lock/AppLockScreen.kt` — lock privacy screen.
- `ui/navigation/Routes.kt`, `ui/navigation/FinTrackAppShell.kt`, `MainActivity.kt`, `FinTrackApplication.kt` — Stage 11 route and DI wiring.

### Tests

- `domain/BackupServiceTest.kt` — deterministic export, checksum validation, idempotent re-import, conflicts and clean restore.
- `domain/PrivacySecurityStage11Test.kt` — privacy posture, minimization, audit retention, app lock and profiles.
- `domain/AmbiguousIndianMessageStage11Test.kt` — conflicting Rs.250/Rs.2,550 event, malformed Indian bank CSV, UPI/VPA redaction and Hinglish evidence fixture.
- Existing `BackupEncryptionAndCsvTest` also passes.

## 2. Room schema/migration

- Schema version: **10 -> 11**.
- New tables: `import_staging_rows`, `import_batches`, `settings_profiles`, `audit_log`, `app_lock_state`.
- Migration is additive-only; existing v10 rows are untouched.
- `app/schemas/.../11.json` was generated during the debug build.
- Instrumented migration execution was not possible because no emulator/device is available; Android test compilation passes.

## 3. Domain/business rules

- Exports use stable dataset manifests, deterministic row ordering and SHA-256 checksums.
- Raw SMS bodies are excluded from the backup dataset vocabulary; provider secrets are structurally absent.
- Encrypted exports use authenticated AES-GCM and reject wrong passwords/tampering without returning plaintext.
- Imports validate format/schema/checksums before staging and require explicit merge policy before commit.
- CSV date and sign conventions are declared by the mapping; malformed rows stay errors.
- Export text redacts full phones, OTPs and full account identifiers while preserving masked identifiers, VPAs and financial references.
- LLM prompts receive only allow-listed structured fields and masked identifiers.
- Audit details are sanitized and retention-bounded; app-lock secrets are outside Room and exports.

## 4. Tests run

- `./gradlew.bat :app:assembleDebug :app:compileDebugAndroidTestKotlin` — **PASS**.
- `./gradlew.bat :app:testDebugUnitTest` — **433 tests, 0 failures**.
- Instrumented migration/UI execution — **not run**, no device/emulator available.

## 5. Acceptance gates

| Gate | Result |
|---|---|
| Versioned deterministic export + checksums | PASS in unit tests |
| Encrypted export/decryption and failure handling | PASS in unit tests |
| Validation/preview before commit | PASS in domain tests; staging tables added |
| Repeated import idempotency | PASS in fake-sink tests |
| Explicit conflicts / no silent overwrite | PASS in domain/UI tests |
| CSV mapping/sign/date handling | PASS, including malformed Indian statement rows |
| Redaction golden fixtures | PASS |
| Restore workflow documentation | Added in `BackupRestoreScreen.kt` and this report |
| Settings profile separation | PASS in profile tests |
| App lock / protected secret path | PASS in JVM fake-vault tests; Keystore implementation compiles |
| LLM minimization | PASS with phone/OTP/full-VPA adversarial fixtures |
| Offline core and non-goals | No bank APIs, cloud sync, push, transfers or SMS deletion added |

## 6. Unresolved risks / deliberate limitations

1. The current Room commit transaction has concrete decoders/upserts for the primary restore dependency set (accounts, categories, transactions, ledger entries, budgets and merchants). The remaining durable datasets are exported and staged but require additional entity decoders/upserts before production clean-install restore can claim full fidelity. This is the main follow-up.
2. `BackupService`'s repository staging lifecycle is complete for the fake/domain path; production callers should create the `ImportBatchEntity` before invoking `stageValidated` (the current ViewModel wiring needs that batch-start call before device restore).
3. Replace-selected UI passes the selection set to the ViewModel, while the service currently derives all real conflicts for `REPLACE_WITH_IMPORTED`; per-row selection enforcement is a follow-up.
4. Settings-screen navigation to the backup route and an app-lock lifecycle observer around `onStop`/`onStart` are wired only as service/shell surfaces; a final navigation/lifecycle polish pass is recommended.
5. Keystore-backed behavior needs an Android device test covering key invalidation and lock-screen configuration changes.
