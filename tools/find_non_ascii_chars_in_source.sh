#!/usr/bin/env bash
#
# find_non_ascii_chars_in_source.sh
#
# Repository-wide non-ASCII check for this project.
#
# The rule this repo works by:
#
#   * SOURCE files are pure ASCII. Java, XML, Gradle, shell, Python, ProGuard
#     and properties files have no reason to contain a single non-ASCII byte:
#     Android string resources use \uXXXX escapes (as they already do here),
#     and comments use -- instead of a typographic dash. Anything else is a
#     change worth looking at twice.
#
#   * DOCUMENTATION and store metadata may use a short, fixed list of
#     typographic characters (dashes, quotes, ellipsis, bullet, arrow, the
#     box-drawing characters used in docs/brand.md, ...). Nothing else.
#
#   * Hidden characters - bidi overrides, zero-width characters, BOM, tag
#     characters, private-use characters - are rejected in EVERY file, and
#     no allowlist can permit them. See tools/check_nonascii.sh.
#
# Usage:
#   tools/find_non_ascii_chars_in_source.sh            # all tracked files
#   tools/find_non_ascii_chars_in_source.sh -v         # also list allowed ones
#   tools/find_non_ascii_chars_in_source.sh --staged   # only staged files
#   tools/find_non_ascii_chars_in_source.sh path ...   # only these paths
#
# As a pre-commit hook (checks what is about to be committed):
#   printf '#!/bin/sh\nexec tools/find_non_ascii_chars_in_source.sh --staged\n' \
#       > .git/hooks/pre-commit && chmod +x .git/hooks/pre-commit
#
# Exit status: 0 clean, 1 disallowed characters found, 2 setup error.

set -u

_HOME2_=$(dirname "$0")
_HOME_=$(cd "$_HOME2_" && pwd)
basedir="$_HOME_/.."
cd "$basedir" || exit 2

CHECK="$_HOME_/check_nonascii.sh"
[ -x "$CHECK" ] || chmod +x "$CHECK" 2>/dev/null
[ -r "$CHECK" ] || { echo "missing $CHECK" >&2; exit 2; }

verbose=0
staged=0
paths=()
for arg in "$@"; do
    case $arg in
        -v|--verbose) verbose=1 ;;
        -s|--staged)  staged=1 ;;
        -h|--help)    sed -n '2,40p' "$0"; exit 0 ;;
        *)            paths+=("$arg") ;;
    esac
done

# Typography that documentation and store text may use. Deliberately short:
# every entry is a visible, printable character with an obvious purpose.
#   2014 em dash   2013 en dash    2026 ellipsis   2022 bullet
#   201C/201D double quotes       2018/2019 single quotes
#   00B7 middle dot               2192 arrow      00D7 multiplication
#   00B1 plus-minus               00B0 degree     2264/2265 <= >=
#   2500/2502/251C/2514 box drawing (the tree in docs/brand.md)
#   22EE vertical ellipsis (the Android overflow menu, in README.md)
TEXT_ALLOW='2014,2013,2026,2022,201C,201D,2018,2019,00B7,2192,00D7,00B1,00B0,2264,2265,2500,2502,251C,2514,22EE'

is_binary_name() {
    case ${1,,} in
        *.png|*.webp|*.jpg|*.jpeg|*.gif|*.ico|*.bmp|*.svgz|*.ttf|*.otf|*.woff|*.woff2|\
        *.jar|*.aar|*.apk|*.so|*.zip|*.gz|*.bz2|*.xz|*.7z|*.keystore|*.jks|*.bundle|\
        *.class|*.dex|*.mp3|*.mp4|*.ogg|*.pdf|*.bin) return 0 ;;
    esac
    return 1
}

is_text_name() {
    case ${1,,} in
        *.md|*.txt|*.svg|*.rst|*.markdown) return 0 ;;
    esac
    return 1
}

# ---- collect the file list -------------------------------------------------
list_files() {
    if (( ${#paths[@]} )); then
        printf '%s\n' "${paths[@]}"
        return
    fi
    if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        if (( staged )); then
            git diff --cached --name-only --diff-filter=ACMR
        else
            git ls-files
        fi
        return
    fi
    find . \( -name .git -o -name build -o -name .gradle -o -name .idea \) -prune -o \
         -type f -print | sed 's|^\./||'
}

files=$(list_files) || exit 2
[ -n "$files" ] || { echo "no files to check"; exit 0; }

# In --staged mode the index is what matters, not the working tree: a file can
# be staged with one content and edited afterwards. Check the staged blobs.
TMPD=''
if (( staged )); then
    TMPD=$(mktemp -d) || exit 2
    trap 'rm -rf "$TMPD"' EXIT
fi

n_src=0
n_txt=0
n_skip=0
bad_files=0
violations=0
opts=()
(( verbose )) && opts+=(-l)

while IFS= read -r f; do
    [ -n "$f" ] || continue
    [ -f "$f" ] || continue

    if is_binary_name "$f"; then
        n_skip=$((n_skip + 1))
        continue
    fi
    # A file with a NUL byte is not text, whatever it is called. (bash cannot
    # hold a NUL in a string, so grep is the wrong tool here.)
    if ! LC_ALL=C tr -d '\000' < "$f" | cmp -s - "$f"; then
        n_skip=$((n_skip + 1))
        continue
    fi

    target=$f
    if [ -n "$TMPD" ]; then
        target="$TMPD/$f"
        mkdir -p "$(dirname "$target")"
        git show ":$f" > "$target" 2>/dev/null || continue
    fi

    if is_text_name "$f"; then
        n_txt=$((n_txt + 1))
        out=$("$CHECK" ${opts[@]+"${opts[@]}"} -a "$TEXT_ALLOW" -- "$target" 2>&1)
    else
        n_src=$((n_src + 1))
        out=$("$CHECK" ${opts[@]+"${opts[@]}"} -- "$target" 2>&1)
    fi
    rc=$?
    [ -n "$TMPD" ] && out=${out//"$TMPD/"/}

    if [ -n "$out" ]; then
        printf '%s\n' "$out"
        if (( rc == 1 )); then
            bad_files=$((bad_files + 1))
            violations=$((violations + $(printf '%s\n' "$out" | grep 'hex=' | grep -vc ' allowed$')))
        fi
    fi
    (( rc == 2 )) && { echo "error while checking $f" >&2; exit 2; }
done <<< "$files"

echo "----------------------------------------------------------------"
printf 'checked %d source file(s) (ASCII only) and %d text file(s) (typography allowed), %d binary skipped\n' \
    "$n_src" "$n_txt" "$n_skip"

if (( bad_files )); then
    printf 'FAIL: %d disallowed character(s) in %d file(s)\n' "$violations" "$bad_files"
    echo
    echo "Source files must be pure ASCII: use \\uXXXX escapes in strings.xml and"
    echo "plain ASCII in code and comments. Hidden or bidi characters are never"
    echo "allowed anywhere - if one shows up in a contribution, reject the commit."
    exit 1
fi

echo "OK: no disallowed non-ASCII characters"
exit 0
