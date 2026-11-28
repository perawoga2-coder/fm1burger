# Burger Studio Lab: start here

คู่มือสำหรับ Claude ที่เปิดหน้าใหม่มาทำงาน Burger ต่อ อ่านไฟล์นี้ก่อน แล้วอ่านอีก 2 ไฟล์ก่อนเริ่มงานทุกครั้ง:

1. `.claude/skills/makerhip-working-rules/SKILL.md`: how to work with the owner (Maker Hip). Follow it.
2. `.claude/skills/makerhip-working-rules/DECISIONS.md`: what is already decided. Do not ask again.

The owner is Thai, a designer, not a coder, and works on an Android tablet. Answer in Thai, short, step by step.

## What this is

Burger Studio is an Android groovebox app (Kotlin, about 29 000 lines, no Compose) that plays together with the
M-VAVE FM-1 and with Maker Hip's STACK firmware (repo `perawoga2-coder/stack`). The "Lab" line (3.x) is the one in
work. The package name stays `com.example.fm1burger` so updates install over earlier versions.

- Work branch: `ccr-94587bba-xqg4fh`. Never push to `main` without the owner's word.
- Version: `build.gradle.kts`, `versionCode` and `versionName` (`3.65-lab`, code 95 at the time of writing). Every
  build that goes to the owner gets a new version.
- The owner's site (repo `Fm1sampa`, `web/makerhip/index.html`) has a Burger download button that stays on 3.55
  until the owner says otherwise.

## Build and check (the order of work)

1. **A bug: write a failing check first** (in `tools/vacuum_check/VacuumCheck.kt` where it fits).
2. **Compile for a plain JVM and run VacuumCheck** (no Android SDK is needed; see `tools/vacuum_check/README.md`).
   Needs from Maven Central: `kotlin-compiler-embeddable` 1.9.24 with `kotlin-stdlib` 1.9.24, `kotlin-reflect`,
   `annotations-13.0`, `kotlin-daemon-embeddable` 1.9.24, `trove4j`, `kotlin-script-runtime` 1.9.24, and
   `org.robolectric:android-all` (the Android classes). The `R` stub is `tools/vacuum_check/stub/R.kt`.

       java -cp <compiler jars> org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-reflect -no-stdlib \
         -cp android-all.jar:kotlin-stdlib-1.9.24.jar -d out \
         src/main/java tools/vacuum_check/stub/R.kt tools/vacuum_check/VacuumCheck.kt
       java -cp out:android-all.jar:kotlin-stdlib-1.9.24.jar VacuumCheckKt tools/vacuum_check/stack_song.txt

   It must end with `ALL OK`.
3. **Commit and push** to the work branch (only when the owner asked for the work).
4. **Build the APK on GitHub**: run the workflow `build.yml` (workflow_dispatch) on the work branch. It publishes a
   pre-release `v<versionName>` with `burgerstudio-lab-v<version>-debug.apk`.
5. **Check the APK** before giving the link: download it from the release, then
   - `keytool -printcert -jarfile <apk>`: SHA256 must be `B7:C6:…:3D:B6` (the Lab line's fixed key), and
   - the version in the manifest must be the new one.
6. **Give the owner the link** (in a code block, to copy).

## Never

- **Never tell the owner to install an older APK over a newer one.** Android refuses it or the app crashes on start;
  the owner nearly lost work this way (3.61 over 3.62). To go back, fix forward with a new version.
- Never put a token, password or key into a file, a commit or a message. Secrets belong in GitHub Secrets only.
- Never send the owner's private data (email and the like) to any outside service.

## Map of the code (the parts most work touches)

| File | What |
|---|---|
| `Model.kt` | `Cfg` (STEPS 128 = 8 bars), `Pat` (length, meter, bars), the data of tracks, pads, steps |
| `Engine.kt` | the audio engine: tracks, pads, mix, the limiter on the master (since 3.59) |
| `StepEditor.kt`, `PrepareBar.kt`, `StepsScreen.kt` | the STEPS window and its bar buttons (1 to 8) |
| `Vacuum.kt` | VACUUM: pulls the whole STACK song off the FM-1 (`VacuumPlan`, `VacuumApply`; CLEAR EVERYTHING, LANES 1-8, DIRECT SPLIT) |
| `StackSong.kt` | STACK's dump record: parse, unpack 7-to-8, the tracks; since 3.64 up to 4 pages of steps (STACK 4.16-beta) |
| `Fm1SampleLink.kt`, `MidiLink.kt`, `DeviceHub.kt` | USB MIDI to the FM-1: send samples to MY 1-3, erase a slot, the dump |
| `Parts.kt`, `TakeAll.kt`, `SongScreen.kt` | song parts Q1-Q6, TAKE ALL, the song list |
| `Store.kt`, `Project.kt` | saving; files live in the app's private storage (samples, inbox, mixes, stems, parts, drumedit) |
| `MainActivity.kt`, `HomeScreen.kt` | the app's frame, Home and its lanes |
| `Backup.kt` | 3.65: BACKUP EVERYTHING / RESTORE (Setup page): files/ and shared_prefs/ in one zip; restore stages first, then the app closes without saving |

The STACK side of VACUUM is documented in `stack/docs/PROTOCOL.md` of the `stack` repo (command 30: SONG, 31/32:
the loop).

## Known

- Every pattern length 1, 2, 4, 8 bars works since 3.63 (3.62's STEPS window showed buttons 5-8 on any length).
- A STACK song of N pages goes into Burger bar b = STACK page (b mod N); a shorter Burger pattern says which bars did
  not come (3.64).
- **The signing key (owner chose ข, a new key, 28 Nov):** `.github/workflows/build.yml` holds the old keystore written
  out in the file (public). Plan: 3.65 adds BACKUP/RESTORE (old key) → the owner backs up → a new key, put in GitHub
  Secrets by the owner → 3.66 reads it from Secrets → install, RESTORE. Still to ask: the new build as a new app id
  beside the old one (recommended: nothing to uninstall until the restore is checked) or the same id.

## Waiting (the owner's words, not yet work)

- A Burger emulator on the web / in the Firmware Hub: wait until after the Burger feature upgrade "when the token is
  updated" (options given: a link in the card; an intro panel on STACK's emulator page; a real web port).
- An AI helper in the app that does the work by chat ("move this file there, lower that"): talked about only.
  Options found: a chat panel in the app on an AI API (works now); Android AppFunctions so Gemini can call the app
  (Android 16+, in preview in 2026). Build the app's list of commands first: both ways use it.
- Folders a project (Samples, Patterns, Mixes, Stems, FM-1): talked about only; it changes how files are kept and
  must move the owner's files without losing any.
