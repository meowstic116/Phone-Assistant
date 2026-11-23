#!/usr/bin/env bash
# Guards the settings screen against the crash that shipped twice: a control attached to the
# layout in one place and handed to a row in another, which Android rejects with
# "The specified child already has a parent" and which no test could see because the screen is
# assembled in code. Two rules, both mechanical:
#
#   1. Only these functions may attach a view to the screen's content column, and each has
#      exactly one job: infoRow (a row), plainButton (a button with no explanation), text,
#      and addApiSettings (its inflated layout).
#   2. A builder that returns a view must not attach it, because the caller will put it
#      somewhere. button() and heading() are the ones that bit us.
#
# Deliberately a text check, not a render test: it needs no emulator, so it runs on every PR.
set -euo pipefail
cd "$(dirname "$0")/.."
file=app/src/main/java/com/phoneassistant/PipSettingsActivity.kt
fail() { echo "settings-layout check FAILED: $*" >&2; exit 1; }

# 1. Every content.addView must sit inside an allowed owner.
allowed='infoRow|plainButton|text|addApiSettings'
while IFS= read -r hit; do
    line=${hit%%:*}
    owner=$(awk -v target="$line" '
        /^    (private |override )?(fun|val|var) / {
            name = $0
            sub(/\(.*/, "", name)
            sub(/^    (private |override )?(fun|val|var) /, "", name)
            current = name
        }
        NR == target { print current; exit }
    ' "$file")
    [[ "$owner" =~ ^($allowed)$ ]] \
        || fail "content.addView at line $line is in '$owner'; only $allowed may attach to the screen"
done < <(grep -n 'content\.addView' "$file")

# 2. button() must build without attaching, so a caller can place it in a row.
body=$(awk '/private fun button\(label: String, click/ {f=1} f {print} f && /^    }/ {exit}' "$file")
[[ -n "$body" ]] || fail "button(label, click) not found; update this check"
if grep -q 'content\.addView' <<< "$body"; then
    fail 'button(label, click) attaches its own view — a noted button would then be added twice'
fi

# 3. A bare button() builds a button that nothing attaches. Keep such builders out of field
#    assignments: 'x = button(...)' is how three controls (Kokoro download, the listening
#    switch, Speak now) silently existed in code while missing from the screen.
if grep -qE '^\s*[A-Za-z_][A-Za-z0-9_]* = button\(' "$file"; then
    fail 'a field is assigned from the bare builder; use plainButton so the button is attached'
fi

# 4. The noted form must add the row exactly once, through infoRow.
grep -q 'content\.addView(row' "$file" || fail 'infoRow no longer adds its row'
count=$(grep -c 'infoRow(label, note, control' "$file")
[[ "$count" -eq 1 ]] || fail "infoRow is called $count times; expected exactly one (from withInfo)"

echo "settings-layout check passed"
