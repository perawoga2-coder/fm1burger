# VACUUM, then TAKE ALL: a check off the tablet

`VacuumCheck.kt` is not part of the app. It reads a song as STACK 4.0-beta's simulator sent it (`stack_song.txt`:
command 30's answers), plans and applies VACUUM on a real `Engine` and `LaneSetup`, then does TAKE ALL's SEND and
RECALL (`PartBank.send` / `recall`, unchanged) and checks what came back.

3.59: `stack_song_405.txt` is a song as STACK 4.05-beta's simulator sends it (the record in STACK's
`app/tests/dump.txt` after its first take: 3/4 at 100 BPM, the loop moved to track 7). The check reads it from the
same folder as the first file (or the second argument) and puts it on the lanes: the loop on lane 7. It also checks
that a loop of 2 or 4 bars gets a FIT of as many bars (3 bars: 4, said).

3.64: `stack_song_416.txt` is a song as STACK 4.16-beta's simulator sends it (the last song in STACK's
`app/tests/dump.txt`: 192 bytes a track, three pages of steps, PLAY MODE PAGE). The check reads its pages and puts
them on a pattern of 4 bars (bar 1 page 1, bars 2 and 3 pages 2 and 3, bar 4 page 1 again) and of 2 bars (page 3
does not come, and the app says so). A record of before 4.16-beta is one page.

It needs the app's classes compiled for a plain JVM: kotlinc 1.9.24, the Android framework classes as a jar
(`org.robolectric:android-all`, from Maven Central) and a stub for `R`:

    kotlinc -cp android-all.jar -d out src/main/java stub/R.kt tools/vacuum_check/VacuumCheck.kt
    java -cp out:android-all.jar:kotlin-stdlib.jar VacuumCheckKt tools/vacuum_check/stack_song.txt
