## [ERR-20260720-001] artifact-transfer-timeout

**Logged**: 2026-07-20T12:16:00+08:00
**Priority**: medium
**Status**: in_progress
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

---
