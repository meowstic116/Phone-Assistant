#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ant_jar="$(realpath "${1:?pass the Apache Ant 1.10.15 jar as an argument}")"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
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
