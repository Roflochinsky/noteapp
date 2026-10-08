# Communications release

## User outcome
Capture a conversation offline, retain edits during unstable internet, gather selected text communications, and deliberately export useful project context. Russian UI, OnePlus 13 / Android 15; minimum Android 14. Existing recording, STT, remote note format, task schema and Action remain compatible.

## Acceptance
1. Recover the verified October 5 reliability patch from its published archive; preserve pending writes, retries and recovered transcription without avoidable paid re-upload. Run current regression gates.
2. Paste text, receive Android shared text, or import a selected UTF-8 text/Markdown file. Preview and explicitly save before persistence. Preserve original text exactly. No implicit STT or remote upload.
3. Store title, source category, project, communication date and optional source link locally. Optional user-authored decisions, commitments, owners, due dates and blockers remain separate from original source text. Unknown facts are never invented.
4. Show local-only state clearly. Filter the communications workspace by project; export selected project context including imported sources, available recording transcripts/notes and existing tasks. Include provenance and observed sync state. Export only on user action via Android document picker or share chooser; no automatic jey access.
5. Preserve saved sources across activity recreation/process restart; recover failed writes without dropping old records. Reject oversized or invalid inputs with a useful message. Cancellation never silently saves.
6. Accessible Russian controls in existing DocTheme. Existing recording remains immediately reachable. UI tests cover save/cancel, errors and project-filtered export.
7. Produce versioned isolated APK, run formatting/static analysis/lint/unit/Robolectric/build gates, independent reviews and privacy scan; publish source commit/PR and release with exact checksums and limits. Never overwrite or uninstall existing app data.

## Architecture (HLD)
A local communications JSON store is separate from the note repository and audio pipeline. Its schema is not sent to GitHub. Source import is explicit and UTF-8 size-bounded. Exports are derived Markdown, not a new remote note schema. The workspace uses existing cached note/task models read-only. Existing note edits and tasks still use their current queue. No ADR-gated STT, remote frontmatter, Action, assistant trigger or audio storage change.

## Boundaries
No direct Telegram/Yandex integration, live private-data testing, credentials in fixtures, phone installation, automatic third-party transmission, fabricated owners/dates, or main merge. Signing keys for previous installations are unavailable: this release must use a distinct application package and disclose that local data is not migrated.
