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
   - **Keep it read-only:** run `npx supabase link --project-ref yjvoxozaoiarwbusyqke` and then
     `npx supabase db push` yourself after each phase. Claude then checks the result with `get_advisors`.

## Do these first

### 1. Lock down the demo project (urgent, 2 minutes)

The demo project is still open: anyone who has the APK can read and rewrite its shipments, readings, alerts and
handovers. Claude is not allowed to touch that project.

1. Open the demo project in the Supabase dashboard, then SQL Editor, then New query.
2. Paste all of `docs/agent/lockdown_demo_project.sql` and click Run.
3. Check the final result table: every row should show `rls_enabled = true` and `policies = 0`.

The old APK will stop syncing, which is intended. No data is deleted.

### 2. Keep or replace the JDK

Nothing on this PC could run the build: `JAVA_HOME` was unset, the only JDK is Oracle 25, and the path pinned in
`gradle.properties` no longer exists (the Red Hat Java extension updated itself). Claude put a portable
Temurin 17 at `D:\cch-tools\jdk-17.0.20.1+1`. It changed nothing on PATH or in the registry.

Either way, set `JAVA_HOME` once so builds work outside Claude's sessions:

```powershell
[Environment]::SetEnvironmentVariable('JAVA_HOME', 'D:\cch-tools\jdk-17.0.20.1+1', 'User')
```

If you install Android Studio instead, point `JAVA_HOME` at its `jbr` folder and delete `D:\cch-tools`.

### 3. Install an emulator (needed for Gate 3 and Gate 5)

The SDK has no emulator or system images, and Claude does not install tools beyond project dependencies. This
is about 5 GB on C:, which has only 14 GB free, so see item 4 first. The two-account flow and the rotation,
background and process-death checks can't be run without an emulator or a connected phone.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" "emulator" "system-images;android-36;google_apis;x86_64"
```

If `cmdline-tools` is missing, install it from Android Studio's SDK Manager, or plug in a phone with USB
debugging enabled instead, which is enough for most of these checks.

### 4. Free space on C:

C: is 96% full (14 GB free). The Gradle cache, the Android SDK and Docker's data all live there. If it fills up,
builds and the local database will start failing.
