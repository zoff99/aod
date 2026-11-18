#!/usr/bin/env python3
"""Check an AoD APK for any trace of notification content access.

    python3 tools/check_apk_privacy.py aod-debug.apk

Reads the string table of every classes*.dex in the APK and fails if it finds
a notification content key or a content-reading API. Because the app is built
with shrinking (see app/proguard-rules.pro), even the unused AndroidX
notification helpers are absent, so a clean APK contains none of these
strings at all - not in our code and not in any library.

Standard library only; no Android SDK needed.
"""
import re
import struct
import sys
import zipfile

# Keys and APIs that would let code read a title or a message body.
FORBIDDEN = [
    "android.title", "android.text", "android.bigText", "android.subText",
    "android.infoText", "android.summaryText", "android.textLines",
    "android.messages", "android.historicMessages", "android.conversationTitle",
    "android.people", "android.people.list", "android.mediaTitle",
    "android.mediaArtist", "android.mediaAlbum", "android.remoteInput",
    "android.remoteInputs", "android.allowGeneratedReplies",
    "tickerText", "getCharSequence", "getCharSequenceArray",
    "getCharSequenceArrayList", "NotificationCompat", "MessagingStyle",
    "RemoteInput",
]

# The only notification keys the app is allowed to use: metadata, never text.
ALLOWED_KEYS = [
    "android.progress", "android.progressMax",
    "android.mediaSession", "android.template",
]


def dex_strings(data):
    """Yield every string in a dex file's string table."""
    count, offset = struct.unpack_from("<II", data, 0x38)
    for i in range(count):
        pos = struct.unpack_from("<I", data, offset + 4 * i)[0]
        # uleb128 length prefix
        shift = 0
        while True:
            byte = data[pos]
            pos += 1
            if not byte & 0x80:
                break
            shift += 7
        end = data.index(b"\x00", pos)
        yield data[pos:end].decode("utf-8", "replace")


def main(path):
    with zipfile.ZipFile(path) as apk:
        dexes = [n for n in apk.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
        if not dexes:
            print("FAIL: no dex files in " + path)
            return 1
        found = {}
        strings = set()
        for name in dexes:
            for s in dex_strings(apk.read(name)):
                strings.add(s)
        for key in FORBIDDEN:
            hits = [s for s in strings if s == key or (key in s and s.startswith("L") and key in s)]
            if hits:
                found[key] = sorted(hits)[:3]

    print("APK: " + path)
    print("dex files: %d, strings: %d" % (len(dexes), len(strings)))
    print("allowed metadata keys present: " + ", ".join(k for k in ALLOWED_KEYS if k in strings))
    if found:
        print("\nFAIL - notification content could be read:")
        for key, hits in sorted(found.items()):
            print("  %-28s e.g. %s" % (key, ", ".join(hits)))
        return 1
    print("\nOK - no notification title or message text can be read by any code in this APK")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
