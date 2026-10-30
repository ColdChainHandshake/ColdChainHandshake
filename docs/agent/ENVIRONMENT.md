# Environment (checked 2026-10-30, Phase 0)

Machine: Windows 11 Home (10.0.26200), Git Bash and PowerShell 5.1.

| Item | State | Notes |
|---|---|---|
| Android Studio / JBR | **not installed** | The JBR path the prompt assumes does not exist |
| System `java` | Oracle 25.0.2 | Too new for Gradle 8.2 and Kotlin 1.9.10 |
| Other Java on the machine | Java 8 in Oracle XE, JRE 25.0.4.1 in the Red Hat extension | Neither fits; AGP 8.2 needs 17 |
| `JAVA_HOME` | **unset** | |
| Pinned `org.gradle.java.home` | **dead path** | Red Hat Java extension moved from 1.56.0 (JRE 21) to 1.58.0 (JRE 25) |
| JDK used for this work | Temurin 17.0.20.1+1 | Portable, `D:\cch-tools\jdk-17.0.20.1+1`, SHA-256 checked against the Adoptium API. PATH and registry untouched |
| `ANDROID_HOME` | **unset** | SDK is at `%LOCALAPPDATA%\Android\Sdk` |
| SDK platforms / build-tools | android-34 / 34.0.0 | |
| Emulator, system images, AVDs | **none** | `emulator/` and `system-images/` are absent |
| SDK command-line tools | **none** | No `sdkmanager`. The `android` CLI installs SDK packages instead |
| `adb devices` | no devices | adb works but is not on PATH: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` |
| Node / npm | 24.7.0 / 11.5.1 | |
| Docker | Desktop 28.3.3, running | Data lives on C: (see disk below) |
| `gh` | signed in, scopes `repo`, `workflow`, `read:org`, `gist` | |
| Supabase CLI | 2.122.0 through `npx supabase` | |
| `android` CLI | 1.1.16717390 | |
| `actionlint` | not installed | Workflow is validated by YAML parse only |
| `claude` CLI | installed | |
| CMake | on PATH | Needed for the Phase 4 host library |

## Disk

C: is 97% full (11 GB free of 317 GB, after 3 GB of local database images). D: has 371 GB free. The Gradle
cache, the Android SDK and Docker's data all sit on C:, so large downloads need watching. Tooling Claude adds
goes on D:. `HANDOFF.md` item 3 covers moving Docker and the Gradle cache.

## MCP servers and skills

| Server | State | Notes |
|---|---|---|
| `supabase` | connected, **read-only** | Project `yjvoxozaoiarwbusyqke`, set literally in `.mcp.json` with `read_only=true`. No `apply_migration` tool. The database did not respond during this check (see `HANDOFF.md`) |
| `context7` | connected | Library docs |
| `desktop-commander` | connected | Not needed so far |
| `github` plugin | **failed** | HTTP 400 on its Authorization header. Not needed, `gh` works |

Android skills are installed under `.claude/skills/` (agp-9-upgrade, edge-to-edge, permissions, intent security,
ANR, R8, Compose performance, Play policy and others). `pr-review-toolkit` agents are available.

`.claude/settings.json` from the kit was not in place at the start of this session. It is now committed, so its
deny and ask rules apply from the next session. The `local.properties` rule was followed by hand before that.

## How to run Gradle on this machine

```bash
export JAVA_HOME='D:\cch-tools\jdk-17.0.20.1+1'
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
export GRADLE_USER_HOME='D:\cch-tools\gradle-home'   # keeps new downloads off C:
./gradlew testDebugUnitTest
```
