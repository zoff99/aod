#!/usr/bin/env bash
#
# check_nonascii.sh
#
# Report non-ASCII characters/bytes that are not in an allowed hex list, and
# ALWAYS report characters that can be used to hide code in a commit, even if
# somebody puts them on the allowed list.
#
# Why the second part matters: a review diff shows glyphs, not code points.
# Right-to-left overrides can make a line read as something completely
# different from what it compiles to ("Trojan Source"), zero-width characters
# can split an identifier into two, and a private-use or tag character is
# invisible in most editors. None of them have any business in this repo, so
# they are rejected unconditionally - an allowlist cannot open that door.
#
# Default mode:
#   - Assumes input is UTF-8.
#   - Allowed hex values are Unicode code points, e.g. 00E9, 0xE9, U+20AC.
#   - Position is character position in the line, starting at 1.
#
# Byte mode (-b):
#   - Treats input as raw bytes.
#   - Allowed hex values are byte values 00-FF.
#   - Position is byte position in the line, starting at 1.
#   - Code-point rules cannot be applied to single bytes, so the dangerous
#     characters are additionally searched for as raw UTF-8 byte sequences
#     (that search runs in BOTH modes).
#
# Requires Bash 4+ for associative arrays.
#
# Exit status:
#   0  nothing disallowed found
#   1  disallowed non-ASCII found
#   2  usage or file error

usage() {
    cat <<'EOF'
Usage: check_nonascii.sh [-a allowed_hex_list] [-b] [-l] [-h] file [file...]

Checks one or more files for non-ASCII characters.

Options:
  -a allowed_hex_list  Allowed non-ASCII values in hex.
                       Values may be separated by spaces, commas, colons,
                       or semicolons.

                       Default mode: Unicode code points.
                       Examples: 00E9, 0xE9, U+00A9, 20AC

                       Never allowed, whatever this list says: bidi
                       overrides and isolates, zero-width and invisible
                       characters, BOM, soft hyphen, tag characters,
                       variation selectors, private-use areas, C1 controls.

  -b                   Byte mode. Treat the file as raw bytes and allowed
                       values as byte values 00-FF.

  -l                   Also list the non-ASCII characters that ARE allowed
                       (marked "allowed", does not change the exit status).
                       Useful for reviewing what a file really contains.

  -h                   Show this help.

Output:
  filename:line:position: char='X' hex=YYYY
  filename:line:position: char='X' hex=YYYY DANGEROUS: <what it is>

Exit status:
  0  no disallowed non-ASCII found
  1  disallowed non-ASCII found
  2  usage or file error
EOF
}

allow_arg=''
byte_mode=0
list_allowed=0

while getopts ':a:bhl' opt; do
    case $opt in
        a) allow_arg=$OPTARG ;;
        b) byte_mode=1 ;;
        l) list_allowed=1 ;;
        h) usage; exit 0 ;;
        :) printf 'Error: option -%s requires an argument.\n' "$OPTARG" >&2; exit 2 ;;
        \?) printf 'Error: unknown option -%s.\n' "$OPTARG" >&2; usage >&2; exit 2 ;;
    esac
done

shift $((OPTIND - 1))

if (( $# < 1 )); then
    usage >&2
    exit 2
fi

if (( BASH_VERSINFO[0] < 4 )); then
    echo 'Error: bash 4 or newer is required.' >&2
    exit 2
fi

# In normal character mode we need a UTF-8 locale so Bash sees multibyte
# characters correctly. If no UTF-8 locale is found, fall back to byte mode.
if (( byte_mode == 0 )); then
    utf8_locale=$(locale -a 2>/dev/null | grep -iE '^(C|en_US)\.(UTF-?8)$' | head -n1)
    if [[ -n $utf8_locale ]]; then
        export LC_ALL=$utf8_locale
    else
        echo 'Warning: no UTF-8 locale found; falling back to byte mode.' >&2
        byte_mode=1
    fi
fi

# ---------------------------------------------------------------------------
# Characters that are never acceptable in this repository.
# ---------------------------------------------------------------------------
declare -A DANGER=()
danger_hex='
00AD 034F 061C 115F 1160 180E
200B 200C 200D 200E 200F
202A 202B 202C 202D 202E
2060 2061 2062 2063 2064
2066 2067 2068 2069
206A 206B 206C 206D 206E 206F
3164 FFA0 FFF9 FFFA FFFB FEFF
1D159 1D173 1D174 1D175 1D176 1D177 1D178 1D179 1D17A
'
for t in $danger_hex; do DANGER[$((16#$t))]=1; done

danger_name() {
    case $1 in
        0x202A|0x202B|0x202D|0x202E) echo "bidi override (Trojan Source)" ;;
        0x202C)                      echo "bidi pop (Trojan Source)" ;;
        0x2066|0x2067|0x2068|0x2069) echo "bidi isolate (Trojan Source)" ;;
        0x200E|0x200F|0x061C)        echo "invisible direction mark" ;;
        0x200B|0x200C|0x200D|0x2060|0xFEFF) echo "zero-width / no-break space character" ;;
        0x00AD)                      echo "soft hyphen (invisible)" ;;
        0x034F)                      echo "combining grapheme joiner (invisible)" ;;
        0x115F|0x1160|0x3164|0xFFA0) echo "Hangul filler (looks like a space)" ;;
        0xFFF9|0xFFFA|0xFFFB)        echo "interlinear annotation control" ;;
        0x2061|0x2062|0x2063|0x2064) echo "invisible mathematical operator" ;;
        0x206A|0x206B|0x206C|0x206D|0x206E|0x206F) echo "deprecated format control" ;;
        *)                           echo "invisible or private-use character" ;;
    esac
}

is_dangerous() {
    local c=$1
    (( c >= 0x80    && c <= 0x9F    )) && return 0   # C1 controls
    (( c >= 0xE000  && c <= 0xF8FF  )) && return 0   # private use area
    (( c >= 0xFE00  && c <= 0xFE0F  )) && return 0   # variation selectors
    (( c >= 0xE0000 && c <= 0xE01EF )) && return 0   # tags + variation supplement
    (( c >= 0xF0000 && c <= 0x10FFFD )) && return 0  # supplementary private use
    [[ -n ${DANGER[$c]:-} ]]
}

# The same characters as raw UTF-8 byte sequences. This runs in both modes and
# also catches them in a file that is not valid UTF-8 at all.
danger_bytes=$'\xe2\x80[\x8b-\x8f\xaa-\xae]|\xe2\x81[\xa0-\xaf]|\xef\xbb\xbf|\xc2\xad|\xd8\x9c|\xe1\xa0\x8e|\xcd\x8f|\xe1\x85[\x9f\xa0]|\xe3\x85\xa4|\xef\xbe\xa0|\xef\xbf[\xb9-\xbb]|\xef\xb8[\x80-\x8f]|\xf3\xa0[\x80-\x81]'

declare -A allowed=()

# Parse the allowed hex list: 00E9 / 0xE9 / U+00E9 / "00E9 00A9 U+20AC"
if [[ -n $allow_arg ]]; then
    IFS=$' \t\n,;:' read -r -a tokens <<< "$allow_arg"
    for token in "${tokens[@]}"; do
        [[ -z $token ]] && continue
        t=$token
        t=${t#0x}; t=${t#0X}; t=${t#U+}; t=${t#u+}
        if [[ ! $t =~ ^[0-9A-Fa-f]+$ ]]; then
            printf 'Error: allowed value %s is not hexadecimal.\n' "'$token'" >&2
            exit 2
        fi
        val=$((16#$t))
        if (( byte_mode && val > 255 )); then
            printf 'Warning: allowed value 0x%X is > 0xFF; ignored in byte mode.\n' "$val" >&2
            continue
        fi
        if (( byte_mode == 0 )) && is_dangerous "$val"; then
            printf 'Warning: U+%04X is never allowed (%s); ignored.\n' \
                "$val" "$(danger_name "$(printf '0x%04X' "$val")")" >&2
            continue
        fi
        allowed[$val]=1
    done
fi

rc=0

for file in "$@"; do
    if [[ -d $file || ! -r $file ]]; then
        printf 'Error: cannot read file %s.\n' "'$file'" >&2
        rc=2
        continue
    fi

    # 1. Raw byte search for the characters that must never appear. Independent
    #    of locale, of the allowlist and of whether the file decodes as UTF-8.
    while IFS= read -r hit; do
        [[ -z $hit ]] && continue
        printf '%s:%s: DANGEROUS: hidden/bidi byte sequence (see the character report below)\n' \
            "$file" "${hit%%:*}"
        (( rc == 0 )) && rc=1
    done < <(LC_ALL=C grep -anE "$danger_bytes" -- "$file" 2>/dev/null)

    # 2. Character (or byte) report with positions. Only lines that contain a
    #    high byte are examined, which keeps this fast on big files.
    hits=$(LC_ALL=C grep -anE $'[\x80-\xff]' -- "$file" 2>/dev/null)
    grep_rc=$?
    if (( grep_rc > 1 )); then
        printf 'Error: cannot scan file %s.\n' "'$file'" >&2
        rc=2
        continue
    fi
    [[ -z $hits ]] && continue

    while IFS= read -r hit; do
        [[ -z $hit ]] && continue
        lineno=${hit%%:*}
        line=${hit#*:}
        len=${#line}

        for ((idx = 0; idx < len; idx++)); do
            c=${line:idx:1}
            [[ -n $c ]] || continue

            code=0
            printf -v code '%d' "'$c" 2>/dev/null || code=0
            if (( code == 0 )); then
                code=$(LC_ALL=C printf '%d' "'$c" 2>/dev/null) || code=0
                [[ $code =~ ^-?[0-9]+$ ]] || code=0
            fi
            (( code < 0 )) && code=$(( code & 0xFF ))
            (( code > 127 )) || continue

            if (( byte_mode )); then
                hex=$(printf '%02X' "$code")
            else
                hex=$(printf '%04X' "$code")
            fi

            if (( byte_mode == 0 )) && is_dangerous "$code"; then
                printf "%s:%d:%d: char='%s' hex=%s DANGEROUS: %s\n" \
                    "$file" "$lineno" "$((idx + 1))" "$c" "$hex" \
                    "$(danger_name "$(printf '0x%s' "$hex")")"
                (( rc == 0 )) && rc=1
                continue
            fi

            if [[ -z ${allowed[$code]:-} ]]; then
                printf "%s:%d:%d: char='%s' hex=%s\n" \
                    "$file" "$lineno" "$((idx + 1))" "$c" "$hex"
                (( rc == 0 )) && rc=1
            elif (( list_allowed )); then
                printf "%s:%d:%d: char='%s' hex=%s allowed\n" \
                    "$file" "$lineno" "$((idx + 1))" "$c" "$hex"
            fi
        done
    done <<< "$hits"
done

exit $rc
