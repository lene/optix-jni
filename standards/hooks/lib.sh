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
