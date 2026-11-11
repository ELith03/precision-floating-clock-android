#!/usr/bin/env bash
# Optional Termux route. Run only from the extracted project directory.
set -euo pipefail
cd -- "$(dirname -- "$0")"
repo="${GITHUB_REPOSITORY:-}"
test -f app/build.gradle.kts || { echo 'Project files are missing.' >&2; exit 1; }
test -n "$repo" || { echo 'Set GITHUB_REPOSITORY=OWNER/REPOSITORY before running this script.' >&2; exit 1; }
command -v gh >/dev/null || { echo 'Install git, gh and unzip in Termux first: pkg install git gh unzip' >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo 'Sign in first: gh auth login --web --git-protocol https --scopes workflow' >&2; exit 1; }
if gh repo view "$repo" >/dev/null 2>&1; then echo "Repository $repo already exists. Stopping without modifying it." >&2; exit 1; fi
[ ! -d .git ] || { echo 'A git checkout already exists here; stopping without modifying it.' >&2; exit 1; }
git init -b main
git add .
git commit -m 'Build native precision floating countdown clock'
gh repo create "$repo" --private --source=. --remote=origin --push
gh run list --repo "$repo" --limit 1
printf '\nOpen the Actions tab, wait for a green build, and download PrecisionClock-debug-APK.\n'
