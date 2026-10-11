#!/usr/bin/env bash
#
# Runs the host probe against every GGUF model on the device, one at a time.
#
# WHY: the only model verified during development was a 270M Gemma. Different architectures
# and quantizations exercise different llama.cpp kernels, and a crash in any of them would
# look exactly like "the app exited while answering". This walks the real model collection
# and reports the exit code of each run — 139 = SIGSEGV, 134 = SIGABRT, 124 = timeout.

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROBE="$HERE/llama_probe"
LOG="${1:-/tmp/llamachat_modelsweep.log}"

: > "$LOG"

find /storage/emulated/0/Models -maxdepth 2 -name '*.gguf' | sort | while read -r model; do
    name="$(basename "$model")"
    size="$(du -h "$model" | cut -f1)"
    {
        echo "=============================================================="
        echo "MODEL : $name"
        echo "SIZE  : $size"
        echo "PATH  : $model"
    } >> "$LOG"

    timeout 300 "$PROBE" "$model" --max 24 --turns 2 --threads 6 >> "$LOG" 2>&1
    code=$?

    {
        echo "EXIT  : $code"
        case "$code" in
            0)   echo "RESULT: OK" ;;
            124) echo "RESULT: TIMEOUT (took longer than 300s)" ;;
            139) echo "RESULT: *** SIGSEGV (segmentation fault) ***" ;;
            134) echo "RESULT: *** SIGABRT (abort) ***" ;;
            *)   echo "RESULT: *** ABNORMAL EXIT $code ***" ;;
        esac
        echo
    } >> "$LOG"
done

echo "=== sweep finished ===" >> "$LOG"
grep -E '^(MODEL|SIZE|EXIT|RESULT)' "$LOG"
