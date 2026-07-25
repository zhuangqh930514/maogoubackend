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
