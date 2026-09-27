#!/bin/sh
# GPU preflight check (Sprint 36 D1). Polls nvidia-smi for free VRAM and GPU
# utilization; exits 0 when the GPU is usable, 1 (with a one-line culprit description
# on stdout) when it's still busy after the retry window. Not a SUITE-line emitter
# itself — invoked by standards/hooks/lib.sh's gpu_preflight_or_skip(), which turns a
# busy verdict into a SKIP (local) or FAIL (CI) for the calling suite script.
#
# Busy means load, not presence: utilization above --max-util-percent, or free VRAM
# below --min-free-mib. Another process merely holding a CUDA context (an idle dev JVM
# on a runner shared with development) is not busy; a process saturating the GPU (the
# 2026-08-07 video2x incident) is. The retry window makes the check wait out a burst:
# the GPU counts as busy only if every sample in the window is.
#
# Runs before the caller launches its own CUDA-using JVM, so the load it sees is
# unambiguously "someone else" — no self-PID filtering needed.
#
# --force / --ignore-busy (or GPU_PREFLIGHT_FORCE=1): skip the busy check entirely and
# exit 0 immediately. An explicit, every-time opt-in, never a default -- the whole point
# of this check is catching flaky GPU-contention test failures before they happen, so a
# caller must knowingly accept that risk each run, not have it silently remembered. Reads
# from the environment (not just a CLI flag) so it flows through gpu_preflight_or_skip()'s
# own no-args invocation of this script without needing changes at every suite call site
# -- export it before invoking the pre-push hook or a suite script directly.
#
# Usage: gpu-preflight.sh [--min-free-mib N] [--max-util-percent N] [--retries N]
#                         [--interval SECS] [--force]
MIN_FREE_MIB="${GPU_PREFLIGHT_MIN_FREE_MIB:-2048}"
MAX_UTIL_PERCENT="${GPU_PREFLIGHT_MAX_UTIL_PERCENT:-50}"
RETRIES="${GPU_PREFLIGHT_RETRIES:-6}"
INTERVAL="${GPU_PREFLIGHT_INTERVAL:-10}"
FORCE="${GPU_PREFLIGHT_FORCE:-0}"

while [ $# -gt 0 ]; do
    case "$1" in
        --min-free-mib) MIN_FREE_MIB="$2"; shift 2 ;;
        --max-util-percent) MAX_UTIL_PERCENT="$2"; shift 2 ;;
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
    GPU=$(nvidia-smi --query-gpu=memory.free,utilization.gpu --format=csv,noheader,nounits \
        2>/dev/null | head -n1 | tr -d ' ')
    FREE_MIB="${GPU%%,*}"
    UTIL="${GPU##*,}"
    REASON=""
    [ -n "$UTIL" ] && [ "$UTIL" -gt "$MAX_UTIL_PERCENT" ] 2>/dev/null \
        && REASON="utilization ${UTIL}% > ${MAX_UTIL_PERCENT}%"
    [ -n "$FREE_MIB" ] && [ "$FREE_MIB" -lt "$MIN_FREE_MIB" ] 2>/dev/null \
        && REASON="free VRAM ${FREE_MIB}MiB < ${MIN_FREE_MIB}MiB"
    [ -z "$REASON" ] && exit 0
    attempt=$((attempt + 1))
    [ "$attempt" -lt "$RETRIES" ] && sleep "$INTERVAL"
done

# Name the biggest other GPU user as the likely culprit (per-process utilization isn't
# reported reliably, so memory is the proxy).
TOP=$(nvidia-smi --query-compute-apps=pid,process_name,used_memory --format=csv,noheader,nounits \
    2>/dev/null | sort -t, -k3 -n -r | head -n1 | tr -d ',')
echo "$REASON${TOP:+ (top process: $TOP MiB)}"
exit 1
