# Release evidence

Scope: docs/specs/2026-10-08-communications-release.md.
Base: ff6df38611c8a89f5b94806c2f26a4d15cbbebf0.
Recovered archive SHA-256: 34352446ea76442f1d2df748180972b0c1ef980a3890b79feac74991c1954920.
Recovered patch applied cleanly; current verification will replace historical test counts.

Execution: authorized assistant cloud filesystem. Repository-local AGENTS.md absent. CLAUDE.md, PRODUCT.md, DESIGN.md and epic/verification instructions read. Beads CLI/database unavailable here; this evidence document does not claim Beads state changes. Latest user instructed autonomous completion using communication-derived requirements; inherited approved toolchain/license authorization used for reinstall. No phone/private backend access.

Slices: reliability recovery; local communications store and context export; Russian UI/share/import; final gates and independent reviews; versioned public release.
Touched scope: recovered patch files; new communications pipeline/UI/tests; MainActivity, FeedScreen and manifest integration; build/gate portability; release and product documentation. Remote note/Action/STT contracts unchanged.

Verification and reviewer verdicts will be recorded against the final source SHA.

Toolchain: JDK17, Android SDK35/build-tools34+35 and Gradle8.13 restored from official vendors. Independent toolchain check matched all four SDK archive SHA-1 values and both license hashes against official repository XML. Transient proxy flags and writable Java/Android home remain outside repository. Gate wrapper now respects explicitly supplied JAVA_HOME/ANDROID_HOME, retaining legacy fallbacks.

Review-driven corrections during implementation: support previously saved Deepgram JSON without paid retranscription; atomic durable WriteQueue replacement and save-before-superseding delete; noBackupFilesDir for local-only imported sources; full draft aggregate bounded to 120k characters before saved-state; new incoming share requires confirmation before replacing active draft; queued task/note edits explicitly unconfirmed in export; picker callbacks serialized rather than silently dropped; UTF-8 import failures in Russian; optional cloud setup and microphone permission guard; signing/package isolation from all prior builds.

Final source candidate: a4402c9d4c7a2a25ccb71a53f2238c3d868cd72b, same tree as published158fadcc310c64ba315b6a02f8515e038cf7ecc4. Full gate passed393tests (386passed,7live-skipped,0fail/errors), lint0errors2existingwarnings1info. Independent native-graphics UI/SAF suite5passed. Six critical mutants plus control killed, full control suite killed; clean restoration verified. Refresh/cancel stale-base race reproduced red then fixed and mutation-protected. Workflow actionlint passed; publication remains gated on completed independent reviews and explicit marker. Public verification report contains bounded claims and device/live-service limits.
