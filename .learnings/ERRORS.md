## [ERR-20260720-001] artifact-transfer-timeout

**Logged**: 2026-07-20T12:16:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: infra

### Summary
Direct SCP upload of the 78 MB Spring Boot JAR is interrupted before completion.

### Error
SFTP upload rejected the remote temporary path, and legacy SCP/rsync transfers were interrupted after a partial upload.

### Context
- Production host: `maogou-server`
- Artifact: `target/maogou-stock-backend-0.1.0.jar`
- Expected deployment requires a SHA-256 match before installation.

### Suggested Fix
Use a small verified class/configuration patch for this deployment, then establish a resumable artifact delivery path for future full releases.

### Metadata
- Reproducible: yes
- Related Files: `target/maogou-stock-backend-0.1.0.jar`

### Resolution
- **Resolved**: 2026-07-20T12:24:00+08:00
- **Commit/PR**: `6a8c482`
- **Notes**: Published a verified small class/configuration patch after full JAR transfer was interrupted; the service health endpoint returned `UP`.

---

## [ERR-20260725-011] backend-working-directory-path

**Logged**: 2026-07-25T19:30:00+08:00
**Priority**: low
**Status**: resolved
**Area**: tests

### Summary
Repeated the `backend/` path after changing the command working directory to `backend`.

### Error
```
sed: backend/src/test/java/...: No such file or directory
```

### Context
- Command ran with working directory `/Users/zqh/coding/fucknidepp/backend`.
- The file path incorrectly began with `backend/` instead of `src/`.

### Suggested Fix
When a command specifies a nested repository as its working directory, use paths relative to that repository.

### Metadata
- Reproducible: yes
- Related Files: `src/test/java/com/maogou/stock/service/impl/research/GlobalDailyResearchExecutorTest.java`

---

## [ERR-20260725-012] remote-sql-quote-truncation

**Logged**: 2026-07-25T19:31:00+08:00
**Priority**: low
**Status**: resolved
**Area**: infra

### Summary
A read-only production SQL query was truncated by nested shell quoting.

### Error
```
ERROR 1064 (42000): SQL syntax error near `LEFT(COALESCE(error_message, ), 500)`
```

### Context
- The query ran through `ssh` and a quoted Python heredoc.
- SQL literal quotes inside the outer remote command were consumed before reaching MySQL.
- The command only read `ai_pipeline_run`; no database write was attempted.

### Suggested Fix
For remote diagnostics, avoid SQL literals where possible or send SQL by a separately quoted stdin payload.

### Metadata
- Reproducible: yes
- Related Files: `ai_pipeline_run`

---

## [ERR-20260725-013] production-snapshot-schema-assumption

**Logged**: 2026-07-25T19:51:00+08:00
**Priority**: low
**Status**: resolved
**Area**: infra

### Summary
A read-only production verification query assumed a `status` column on daily decision snapshots.

### Error
```
ERROR 1054 (42S22): Unknown column 'status' in 'field list'
```

### Context
- The user projection run query completed and showed a `PARTIAL_SUCCESS` result before the second read-only query failed.
- `ai_daily_decision_snapshot` has no `status` column; pipeline status belongs to `ai_pipeline_run`.

### Suggested Fix
Inspect the entity or schema before composing cross-table production diagnostics.

### Metadata
- Reproducible: yes
- Related Files: `ai_daily_decision_snapshot`, `ai_pipeline_run`

---

## [ERR-20260725-014] production-python-stdout-encoding

**Logged**: 2026-07-25T19:52:00+08:00
**Priority**: low
**Status**: resolved
**Area**: infra

### Summary
A production API verification script failed while printing Chinese JSON fields through an ASCII-only Python stdout.

### Error
```
UnicodeEncodeError: 'ascii' codec can't encode characters
```

### Context
- The authenticated HTTP requests completed before output serialization.
- The remote shell locale does not provide UTF-8 stdout to Python 3.6.

### Suggested Fix
Use `ensure_ascii=True` or write UTF-8 bytes explicitly in remote diagnostic scripts.

### Metadata
- Reproducible: yes
- Related Files: production diagnostic script

---

## [ERR-20260725-015] nested-backend-path-recurrence

**Logged**: 2026-07-25T19:54:00+08:00
**Priority**: low
**Status**: resolved
**Area**: tests

### Summary
Repeated the repository directory prefix after switching the command working directory to `backend`.

### Suggested Fix
Use `src/...` paths when the command workdir is already the backend repository.

### Metadata
- Reproducible: yes
- See Also: ERR-20260725-011

---

## [ERR-20260725-016] formal-snapshot-helper-omitted

**Logged**: 2026-07-25T19:54:00+08:00
**Priority**: low
**Status**: resolved
**Area**: backend

### Summary
The initial immutable sample snapshot implementation referenced a JSON-array conversion helper that did not exist in `AiAnalysisServiceImpl`.

### Error
```
cannot find symbol: method treeList(JsonNode, Class<...>)
```

### Suggested Fix
Keep the sample JSON parsing helper local to the service and compile before adding further behavior.

### Metadata
- Reproducible: yes
- Related Files: `src/main/java/com/maogou/stock/service/impl/AiAnalysisServiceImpl.java`

---

## [ERR-20260725-017] response-dto-field-assumption

**Logged**: 2026-07-25T19:56:00+08:00
**Priority**: low
**Status**: resolved
**Area**: tests

### Summary
The historical-report test asserted a response field that the public report DTO does not expose.

### Error
```
cannot find symbol: method reportDate()
```

### Suggested Fix
Assert only DTO fields actually exposed by the API and validate internal report date through persistence tests when needed.

### Metadata
- Reproducible: yes
- Related Files: `src/test/java/com/maogou/stock/service/impl/AiAnalysisServiceImplHistoricalSnapshotTest.java`

---

## [ERR-20260725-018] local-ai-client-mock-jvm-incompatibility

**Logged**: 2026-07-25T19:57:00+08:00
**Priority**: medium
**Status**: mitigated
**Area**: tests

### Summary
Mockito inline mocking cannot instrument `LocalAiClient` under the local Java 25 runtime because the bundled Byte Buddy version supports Java 23 at most.

### Error
```
Java 25 (69) is not supported by the current version of Byte Buddy
```

### Suggested Fix
Run full Mockito class-mocking tests with the supported Java 17 toolchain or upgrade the test dependency. Keep the current regression as a pure immutable-snapshot parsing contract test.

### Metadata
- Reproducible: yes
- Related Files: `src/test/java/com/maogou/stock/service/impl/AiAnalysisServiceImplHistoricalSnapshotTest.java`

---

## [ERR-20260725-019] release-temp-artifact-content-mismatch

**Logged**: 2026-07-25T20:05:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: infra

### Summary
A reused production temporary JAR path retained content from the previous release, so SHA-256 verification rejected the deployment.

### Error
```
maogou-stock-backend-0.1.0.jar failed verification -- update retained
```

### Suggested Fix
Upload each backend release to a commit-qualified temporary filename and compare hashes before replacing the live artifact.

### Metadata
- Reproducible: yes
- Related Files: `target/maogou-stock-backend-0.1.0.jar`

---
