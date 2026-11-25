# VACUUM, then TAKE ALL: a check off the tablet

`VacuumCheck.kt` is not part of the app. It reads a song as STACK 4.0-beta's simulator sent it (`stack_song.txt`:
command 30's answers), plans and applies VACUUM on a real `Engine` and `LaneSetup`, then does TAKE ALL's SEND and
RECALL (`PartBank.send` / `recall`, unchanged) and checks what came back.

It needs the app's classes compiled for a plain JVM: kotlinc 1.9.24, the Android framework classes as a jar
(`org.robolectric:android-all`, from Maven Central) and a stub for `R`:

    kotlinc -cp android-all.jar -d out src/main/java stub/R.kt tools/vacuum_check/VacuumCheck.kt
    java -cp out:android-all.jar:kotlin-stdlib.jar VacuumCheckKt tools/vacuum_check/stack_song.txt
