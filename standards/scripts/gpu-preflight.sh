#!/bin/sh
# GPU preflight check (Sprint 36 D1). Polls nvidia-smi for free VRAM and foreign
# compute processes; exits 0 when the GPU looks free, 1 (with a one-line culprit
# description on stdout) when it's still busy after the retry window. Not a
# SUITE-line emitter itself — invoked by standards/hooks/lib.sh's
# gpu_preflight_or_skip(), which turns a busy verdict into a SKIP (local) or
# FAIL (CI) for the calling suite script.
#
# Runs before the caller launches its own CUDA-using JVM, so --query-compute-apps
# at that moment is unambiguously "someone else" — no self-PID filtering needed.
#
# --force / --ignore-busy (or GPU_PREFLIGHT_FORCE=1): skip the busy check entirely and
# exit 0 immediately. An explicit, every-time opt-in, never a default -- the whole point
# of this check is catching flaky GPU-contention test failures before they happen, so a
# caller must knowingly accept that risk each run, not have it silently remembered. Reads
# from the environment (not just a CLI flag) so it flows through gpu_preflight_or_skip()'s
# own no-args invocation of this script without needing changes at every suite call site
# -- export it before invoking the pre-push hook or a suite script directly.
#
# Usage: gpu-preflight.sh [--min-free-mib N] [--retries N] [--interval SECS] [--force]
MIN_FREE_MIB="${GPU_PREFLIGHT_MIN_FREE_MIB:-2048}"
RETRIES="${GPU_PREFLIGHT_RETRIES:-6}"
INTERVAL="${GPU_PREFLIGHT_INTERVAL:-10}"
FORCE="${GPU_PREFLIGHT_FORCE:-0}"

while [ $# -gt 0 ]; do
    case "$1" in
        --min-free-mib) MIN_FREE_MIB="$2"; shift 2 ;;
        --retries) RETRIES="$2"; shift 2 ;;
        --interval) INTERVAL="$2"; shift 2 ;;
        --force|--ignore-busy) FORCE=1; shift ;;
        *) shift ;;
    esac
done

if [ "$FORCE" = "1" ]; then
    echo "gpu-preflight: FORCE requested -- skipping busy check, GPU contention risk accepted" >&2
    exit 0
fi

command -v nvidia-smi >/dev/null 2>&1 || exit 0   # no GPU tooling; not this script's call

attempt=0
while [ "$attempt" -lt "$RETRIES" ]; do
    FREE_MIB=$(nvidia-smi --query-gpu=memory.free --format=csv,noheader,nounits 2>/dev/null | head -n1)
    PROCS=$(nvidia-smi --query-compute-apps=pid,process_name,used_memory --format=csv,noheader 2>/dev/null)
    BUSY=0
    [ -n "$PROCS" ] && BUSY=1
    [ -n "$FREE_MIB" ] && [ "$FREE_MIB" -lt "$MIN_FREE_MIB" ] 2>/dev/null && BUSY=1
    [ "$BUSY" -eq 0 ] && exit 0
    attempt=$((attempt + 1))
    [ "$attempt" -lt "$RETRIES" ] && sleep "$INTERVAL"
done

if [ -n "$PROCS" ]; then
    printf '%s\n' "$PROCS" | head -n1 | tr ',' ' '
else
    echo "free VRAM ${FREE_MIB:-0}MiB < ${MIN_FREE_MIB}MiB"
fi
exit 1
