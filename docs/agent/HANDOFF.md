# Handoff: what the owner needs to do

Newest and most urgent first. Questions come first; work continues on everything they don't block.

## Questions for you

### Q1. Can Claude write to the dev Supabase project? (blocks Gate 1 and Gate 3 sign-off)

`.mcp.json` points at project `yjvoxozaoiarwbusyqke` with `read_only=true`, so the `apply_migration` tool is not
available and the prompt's "apply to the dev project through MCP" step can't happen. Claude did not remove the
flag, because that is your access decision.

The database also timed out on `list_tables` and `list_migrations`, so the project may be paused.

1. Open https://supabase.com/dashboard/project/yjvoxozaoiarwbusyqke and restore the project if it is paused.
2. Confirm this is the intended dev project. It is not the demo project (`ljqwaqesiszczixbclwg`).
3. Choose one:
   - **Let Claude apply migrations:** delete `&read_only=true` from the URL in `.mcp.json`, then start a new
     Claude Code session. Gate 1 and Gate 3 can then finish without you.
   - **Keep it read-only:** run these yourself after each phase, and Claude checks the result with `get_advisors`:

     ```powershell
     npx supabase login
     npx supabase link --project-ref yjvoxozaoiarwbusyqke
     npx supabase db push
     ```

     `link` asks for the database password, which you can reset in the project's database settings.

## Do these first

### 1. Lock down the demo project (urgent, 2 minutes)

The demo project is still open: anyone who has the APK can read and rewrite its shipments, readings, alerts and
handovers. Claude is not allowed to touch that project.

1. Open the demo project in the Supabase dashboard, then SQL Editor, then New query.
2. Paste all of `docs/agent/lockdown_demo_project.sql` and click Run.
3. Check the final result table: every row should show `rls_enabled = true` and `policies = 0`.

The old APK will stop syncing, which is intended. No data is deleted.

### 2. Set `JAVA_HOME` and `ANDROID_HOME`

Nothing on this PC could build the app. `JAVA_HOME` was unset, and the path pinned in `gradle.properties` no longer
exists because the Red Hat Java extension updated itself. The JDKs that are installed don't fit: Oracle 25 is too
new, and Oracle XE's bundled Java 8 is too old. The build needs JDK 17.

Claude put a portable Temurin 17 at `D:\cch-tools\jdk-17.0.20.1+1`. It changed nothing on PATH or in the registry.
Set both variables once, then open a new terminal:

```powershell
[Environment]::SetEnvironmentVariable('JAVA_HOME', 'D:\cch-tools\jdk-17.0.20.1+1', 'User')
[Environment]::SetEnvironmentVariable('ANDROID_HOME', "$env:LOCALAPPDATA\Android\Sdk", 'User')
```

If you install Android Studio instead, point `JAVA_HOME` at its `jbr` folder and delete `D:\cch-tools`.

Until task 1.1 is ticked in `PROGRESS.md`, plain Gradle commands still fail on the dead path in `gradle.properties`.

### 3. Free space on C:

C: is 97% full (11 GB free), and the local database images just used 3 GB of it. If it fills up, builds and the
local database will start failing. Do this before item 4. In order of effect:

1. **Move Docker's data to D:.** Docker Desktop, Settings, Resources, Advanced, Disk image location. Choose a
   folder on D: and click Apply.
2. **Move the Gradle cache to D:.** Run this, then open a new terminal. The cache rebuilds itself.

   ```powershell
   [Environment]::SetEnvironmentVariable('GRADLE_USER_HOME', 'D:\gradle-home', 'User')
   ```

3. **See what else is there.** `docker system df` shows image space. Several images belong to your other projects,
   so remove only what you recognise with `docker image rm <name>`.

### 4. Install an emulator (needed for Gate 3 and Gate 5)

The SDK has no emulator or system images, and Claude does not install tools beyond project dependencies. The
two-account flow and the rotation, background and process-death checks can't be run without one.

The Android SDK's own `sdkmanager` isn't installed here, so use the `android` CLI, which is:

```powershell
android sdk install emulator
android emulator create
android emulator list
android emulator start <name from the list>
```

`android emulator create` is taken from the CLI's help and has not been run on this PC. It downloads a system
image of several GB onto C:, which is why item 3 comes first.

A phone is a simpler alternative: enable USB debugging, plug it in, and accept the prompt. That covers most of
these checks, and `adb devices` will list it. `adb` is not on PATH; it is at
`%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`.

### 5. Optional: install `actionlint`

Claude can only check the GitHub Actions workflow as valid YAML. `actionlint` checks it properly, and Claude will
use it if it is on PATH.

```powershell
scoop install actionlint
```
