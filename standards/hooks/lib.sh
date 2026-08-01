# Shared helpers for hook policy checks (Sprint 28.2). POSIX sh; source me.

commits_in_range() {
    # rev-list may fail for unknown remote SHAs (e.g. force pushes); treat as empty
    git rev-list --no-merges "$1" 2>/dev/null || true
}

commit_files() {
    git diff-tree -r --no-commit-id --name-only "$1"
}

commit_modified_files() {
    # modified or deleted only — newly added files are not "changes to existing"
    git diff-tree -r --no-commit-id --name-only --diff-filter=MD "$1"
}

commit_subject() {
    git log -1 --format=%s "$1"
}

commit_is_wip() {
    case "$(commit_subject "$1")" in
        WIP:*) return 0 ;;
        *) return 1 ;;
    esac
}

commit_has_trailer() {
    # trailer must carry a non-empty value: "Key: reason"
    git log -1 --format=%B "$1" | grep -q "^$2: ..*"
}

short_ref() {
    git log -1 --format='%h %s' "$1"
}

# --- pre-push change detection ----------------------------------------------
#
# git runs pre-push with "<local_ref> <local_sha> <remote_ref> <remote_sha>" lines
# on stdin. Three invocation contexts have to work, and testing only whether stdin
# is a terminal distinguishes just two of them:
#
#   real push          stdin is a pipe carrying the ref lines -> use them
#   manual, terminal   stdin is a TTY -> must NOT read; `read` would block waiting
#                      for a human. Compare the branch against origin/main instead.
#   manual, no TTY     an agent, script or CI job. stdin is empty and closed, so
#                      `read` hits EOF immediately and yields nothing -> also fall
#                      back to comparing against origin/main.
#
# The third case is the trap. `[ -t 0 ]` is false there, so a hook that treats
# "not a TTY" as "git gave me refs" takes the protocol branch, reads nothing, and
# hands every caller an empty change set. The caller then skips all its checks and
# exits 0 — a false pass that is indistinguishable from a clean run. That is how a
# push containing CI-config changes sailed through the gate untested.
#
# So the TTY test stays, but only as a hang guard: what makes this correct is the
# fallback when the read produces nothing. Do not "simplify" this back into a
# single [ -t 0 ] branch.
#
# Emits "<base>..<local_sha>" ranges, one per line.
# Returns 2 when the push only deletes a branch, so callers can exit deliberately.
push_ranges() {
    _ranges=""
    _deleting=0

    if [ ! -t 0 ]; then
        while read -r _local_ref _local_sha _remote_ref _remote_sha; do
            [ -z "$_local_sha" ] && continue
            case "$_local_sha" in
                0000000000000000000000000000000000000000) _deleting=1; continue ;;
            esac
            case "$_remote_sha" in
                ''|0000000000000000000000000000000000000000)
                    # New branch on the remote: diff from where it left main.
                    _base=$(git merge-base origin/main "$_local_sha" 2>/dev/null \
                            || git rev-list --max-parents=0 "$_local_sha" | tail -n 1) ;;
                *)  _base="$_remote_sha" ;;
            esac
            _ranges="${_ranges}${_base}..${_local_sha}
"
        done
    fi

    if [ -z "$_ranges" ]; then
        [ "$_deleting" = "1" ] && return 2
        _base=$(git merge-base origin/main HEAD 2>/dev/null \
                || git merge-base main HEAD 2>/dev/null)
        [ -n "$_base" ] && _ranges="${_base}..HEAD
"
    fi

    printf '%s' "$_ranges"
}

# Files touched by the given ranges, deduplicated.
changed_files() {
    for _range in "$@"; do
        [ -n "$_range" ] && git diff --name-only "$_range"
    done | sort -u
}

# $1 = the detected file list. An empty set on a branch that has commits to push
# means detection failed; reporting that as "nothing to check" is the bug this
# whole helper exists to prevent, so refuse to continue.
assert_detection() {
    [ -n "$1" ] && return 0
    if [ -n "$(git rev-list origin/main..HEAD 2>/dev/null | head -n 1)" ]; then
        echo "pre-push: detected no changed files, but HEAD is ahead of origin/main." >&2
        echo "          Change detection is broken — refusing to report a clean run." >&2
        echo "          (If this push genuinely changes no files, say so explicitly.)" >&2
        return 1
    fi
    return 0
}
