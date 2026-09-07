# Code Validation: MOB-25129/upgrade-messaging-test-app

## Summary
The platform-install script is directionally correct for AGP 8.2 + API 37 on ubuntu-24.04, but it can abort before the `android-37.0` fallback runs, and the CI fix is not yet on the remote branch.

---

## Code Issues

### CRITICAL: CI fix is uncommitted on the remote branch

**File:** `Makefile:58-60`, `scripts/ensure-testapp-android-platform.sh` (untracked), `code/gradle.properties:17` (unstaged)

**Problem:**
`origin/MOB-25129/upgrade-messaging-test-app` only contains the testapp SDK 37 bump (`code/testapp/build.gradle.kts`). The Makefile hook, install script, and `gradle.properties` change exist only in the local working tree.

Remote `assemble-app` still runs:
```makefile
assemble-app:
	(./code/gradlew -p code/$(TEST-APP-FOLDER-NAME) assemble)
```

**Fix:**
Stage and push all three CI-fix artifacts before merge:
```bash
git add scripts/ensure-testapp-android-platform.sh Makefile code/gradle.properties
```

---

### HIGH: `set -e` prevents `android-37.0` fallback when `sdkmanager` fails

**File:** `scripts/ensure-testapp-android-platform.sh:16,65-71`

**Problem:**
```bash
set -euo pipefail
...
yes | "$sdkmanager" "$SDK_PACKAGE"

if [[ ! -d "$platforms_dir/$PLATFORM_DIR_NAME" ]] && [[ -d "$platforms_dir/android-37.0" ]]; then
    cp -R "$platforms_dir/android-37.0" "$platforms_dir/$PLATFORM_DIR_NAME"
fi
```

ubuntu-24.04 GitHub runner images can ship API 37 only as `platforms/android-37.0` while AGP 8.2 resolves `compileSdk = 37` to `platforms/android-37` ([actions/runner-images#13859](https://github.com/actions/runner-images/issues/13859)). If `platforms;android-37` install fails (network, license, package mismatch) but `android-37.0` is already present, `set -e` exits at line 66 and the copy/symlink fallback never runs.

**Fix:**
Do not let `sdkmanager` failure abort before the fallback path:
```bash
if ! yes | "$sdkmanager" --sdk_root="$sdk_root" "$SDK_PACKAGE"; then
    echo "sdkmanager install of $SDK_PACKAGE failed; checking android-37.0 fallback..." >&2
fi

if [[ ! -e "$platforms_dir/$PLATFORM_DIR_NAME" ]] && [[ -d "$platforms_dir/android-37.0" ]]; then
    echo "Linking $PLATFORM_DIR_NAME -> android-37.0 for AGP 8.2 compatibility..."
    ln -sfn android-37.0 "$platforms_dir/$PLATFORM_DIR_NAME"
fi
```

Prefer checking `-e` (covers symlink) over `-d` only.

---

### MEDIUM: `cp -R` fallback duplicates the platform SDK on every cold CI run

**File:** `scripts/ensure-testapp-android-platform.sh:68-71`

**Problem:**
```bash
cp -R "$platforms_dir/android-37.0" "$platforms_dir/$PLATFORM_DIR_NAME"
```

A full copy adds ~50–100MB and extra I/O on each runner where only `android-37.0` exists. A symlink satisfies AGP’s path lookup and is the workaround recommended for this runner-image behavior.

**Fix:**
Replace `cp -R` with `ln -sfn android-37.0 "$platforms_dir/$PLATFORM_DIR_NAME"`.

---

### MEDIUM: Makefile invokes script via `./` without `bash`, relying on executable bit

**File:** `Makefile:59`

**Problem:**
```makefile
@./scripts/ensure-testapp-android-platform.sh
```

Git only preserves the executable bit if the file is committed `+x`. A fresh checkout with `100644` mode yields `Permission denied` before Gradle runs. Other repos in this org typically invoke shell scripts explicitly.

**Fix:**
```makefile
@bash ./scripts/ensure-testapp-android-platform.sh
```

---

### MEDIUM: `android.suppressUnsupportedCompileSdk=37` does not address the reported CI failure

**File:** `code/gradle.properties:17`

**Problem:**
```properties
android.suppressUnsupportedCompileSdk=37
```

The CI error is a missing platform directory (`platforms/android-37`), not an AGP “unsupported compileSdk” warning. This property is applied project-wide under `code/` even though only `:testapp` uses SDK 37; `:messaging` remains on BuildConstants SDK 34.

**Fix:**
Drop this line unless CI logs show a separate AGP warning-as-error for SDK 37. If it is needed, scope it to the testapp module (e.g., `code/testapp/gradle.properties`) instead of the shared root file.

---

### LOW: License acceptance failures are silently ignored

**File:** `scripts/ensure-testapp-android-platform.sh:65`

**Problem:**
```bash
yes | "$sdkmanager" --licenses >/dev/null 2>&1 || true
```

Failures are discarded (`|| true`) and output is suppressed, so a license rejection surfaces later as an opaque `sdkmanager` install failure.

**Fix:**
Accept licenses without masking errors, or gate install on exit status:
```bash
yes | "$sdkmanager" --sdk_root="$sdk_root" --licenses
```

On GHA, licenses are usually pre-accepted; this line may be removable entirely.

---

### LOW: Early-return idempotency skips repair of a broken `android-37` directory

**File:** `scripts/ensure-testapp-android-platform.sh:57-60`

**Problem:**
```bash
if [[ -d "$platforms_dir/$PLATFORM_DIR_NAME" ]]; then
    echo "Android SDK platform $PLATFORM_DIR_NAME already installed."
    return 0
fi
```

A partial/corrupt `android-37` directory (failed prior copy, empty dir) short-circuits reinstall and leaves CI failing opaquely at Gradle.

**Fix:**
Validate `source.properties` (or `android.jar`) inside the directory before returning early; otherwise remove and reinstall/link.

---

## Review Inputs Used
- `review_plan.json` — not present in workspace; reviewed from user-supplied CI context and branch diff
- `context_brief.md` — not present; used PR description and `build-and-test.yml` reusable workflow
- `review_obligations.json` — not present
- `changed_file_inventory.md` — not present; inventory derived from `git diff` and working tree
- `reviewer_notes` — not present

---

## Exploration Coverage
- Entry points checked: `Makefile:assemble-app`, `.github/workflows/build-and-test.yml` → `aepsdk-commons` `android-build-and-test.yml` `build-app` job (`make assemble-app` on `ubuntu-24.04`)
- Callers/callees traced: CI `build-app` → `make assemble-app` → `ensure-testapp-android-platform.sh` → `sdkmanager` → `./code/gradlew -p code/testapp assemble`
- Blast radius checked: `:messaging` and `:messagingtestutils` still use `BuildConstants` SDK 34; only `:testapp` targets 37
- Runtime/data flow followed: missing `platforms/android-37` at AGP platform resolution before compilation

---

## Invariants & Type Boundaries
- **Key invariants reviewed:** `compileSdk`/`targetSdk` belong on `android {}` (not `defaultConfig`); library modules stay on BuildConstants SDK 34; testapp alone uses API 37
- **Illegal / ambiguous states found:** `platforms/android-37` vs `platforms/android-37.0` directory naming mismatch for AGP 8.2 + `compileSdk = 37`
- **Boundary validation / serialized contract risks:** none material beyond SDK path resolution

---

## Summary
- Critical: 1
- High: 1
- Medium: 3
- Low: 2

**Recommendation:** REQUEST CHANGES
