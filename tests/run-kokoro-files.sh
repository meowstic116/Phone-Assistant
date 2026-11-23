#!/usr/bin/env bash
# Extracts and verifies the Kokoro model archive layout. The Apache Ant jar is a normal app
# dependency (the app unpacks model archives with it), so its version comes from
# app/build.gradle.kts. Pass the jar's path as an argument to use exactly that copy;
# otherwise this script finds one in the Gradle cache or a local Maven checkout, and as a
# last resort downloads it from Maven Central and checks it against Central's own SHA-256.
set -euo pipefail
cd "$(dirname "$0")/.."
ant_ver="$(sed -n 's/.*org\.apache\.ant:ant:\([0-9][0-9.]*\).*/\1/p' app/build.gradle.kts | head -1)"
[[ -n "$ant_ver" ]] || { echo "could not find the org.apache.ant:ant version in app/build.gradle.kts" >&2; exit 1; }
ant_jar="${1:-}"
if [[ -z "$ant_jar" ]]; then
    for candidate in \
        "$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/org.apache.ant/ant/$ant_ver" -name "ant-$ant_ver.jar" -print -quit 2>/dev/null)" \
        "$HOME/.m2/repository/org/apache/ant/ant/$ant_ver/ant-$ant_ver.jar"; do
        [[ -f "$candidate" ]] && { ant_jar="$candidate"; break; }
    done
fi
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
if [[ -z "$ant_jar" ]]; then
    echo "Ant $ant_ver not cached locally; downloading from Maven Central…" >&2
    base="https://repo1.maven.org/maven2/org/apache/ant/ant/$ant_ver/ant-$ant_ver.jar"
    curl -fsSL -o "$tmp/ant.jar" "$base"
    curl -fsSL -o "$tmp/ant.jar.sha256" "$base.sha256"
    printf '%s  %s\n' "$(tr -d ' \n' < "$tmp/ant.jar.sha256")" "$tmp/ant.jar" | sha256sum -c - >/dev/null
    ant_jar="$tmp/ant.jar"
fi
ant_jar="$(realpath "$ant_jar")"
python3 - "$tmp" <<'PY'
import io, pathlib, sys, tarfile
root = pathlib.Path(sys.argv[1])
pack = 'kokoro-int8-en-v0_19'
for name in ('ok', 'link', 'missing'):
    with tarfile.open(root / (name + '.tar.bz2'), 'w:bz2', format=tarfile.GNU_FORMAT) as tar:
        for leaf in ('tokens.txt', 'voices.bin', 'model.int8.onnx', '../../escape', 'espeak-ng-data/en_dict'):
            entry = tarfile.TarInfo(f'{pack}/{leaf}')
            if leaf == 'voices.bin' and name == 'link':
                entry.type = tarfile.SYMTYPE
                entry.linkname = 'tokens.txt'
                tar.addfile(entry)
            elif not (leaf == 'voices.bin' and name == 'missing'):
                entry.size = 3
                tar.addfile(entry, io.BytesIO(b'abc'))
PY
kotlinc -cp "$ant_jar" app/src/main/java/com/phoneassistant/KokoroFiles.kt tests/KokoroFilesTest.kt \
  -include-runtime -d "$tmp/tests.jar"
java -cp "$tmp/tests.jar:$ant_jar" com.phoneassistant.KokoroFilesTestKt "$tmp"
