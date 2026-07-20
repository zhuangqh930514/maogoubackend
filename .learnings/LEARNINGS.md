## [LRN-20260720-001] correction

**Logged**: 2026-07-20T12:26:00+08:00
**Priority**: high
**Status**: pending
**Area**: infra

### Summary
The Tencent document is a destination synchronized from the Maogou database, not a source to import into Maogou.

### Details
The initial implementation added a public-document reader. The user clarified that the nightly task must export the Maogou database's order data into Tencent Docs.

### Suggested Action
Disable the incorrect reader, inspect the database order schema, then implement a database-to-Tencent-Docs writer using authorized Tencent document access.

### Metadata
- Source: user_feedback
- Related Files: `src/main/java/com/maogou/stock/service/QqOrderDocumentSyncService.java`
- Tags: sync-direction, tencent-docs, deployment

---
