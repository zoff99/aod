# AoD brand & promo assets

Everything needed to make screenshots, banners, stickers or a store listing
without redrawing the icon. The artwork is original to this project.

## Files

| File | What it is | Use it for |
| --- | --- | --- |
| `docs/icon.svg` | master icon: rounded-square card, transparent outside | anywhere the icon sits on a background of its own — README, website, press kit, print |
| `docs/icon-square.svg` | same art, full square, no corners, no alpha | store listing icon (Google Play masks it for you) |
| `docs/icon-monochrome.svg` | the ring + dot alone, one colour | watermarks, footers, stickers, dark-on-light print, themed launcher layer |
| `docs/render_assets.py` | renders the PNGs from the same numbers | regenerating `fastlane` images and one-off promo exports |
| `fastlane/metadata/android/en-US/` | store text + images | Play listing via `fastlane supply` |

All three SVGs are plain, hand-written, dependency-free vectors: no filters, no
masks, no embedded fonts, no external references. They open in Inkscape,
Illustrator, Figma, a browser, or a text editor.

## The mark

One ring, one dot. The dot sits on the ring at **-45°** (upper right). Nothing
else — no glow, no lens, no badge, no second accent colour.

Design units (the card is 58 × 58; every SVG scales this space):

| Element | Value |
| --- | --- |
| card | 58 × 58, corner radius 15 |
| card gradient | `#171C28` top-left → `#0B0E15` bottom-right |
| hairline on the card edge | 0.6 wide, `#FFFFFF` at 10.2 % |
| ring | centre (29, 29), radius **16** (centre line), stroke **3.4** |
| dot | radius **4.6**, centre (40.3137, 17.6863) = 29 ± 16·cos 45° |

Ratios that must survive any resizing: ring radius = 0.276 × card side,
stroke = 0.0586, dot radius = 0.0793, corner = 0.2586.

### Palette

| Name | Hex | Notes |
| --- | --- | --- |
| Card top | `#171C28` | gradient start, also the dark UI surface |
| Card bottom | `#0B0E15` | gradient end, near-black background |
| Signal cyan | `#35D6F0` | the ring, accents, links on dark |
| Dot / text | `#FFFFFF` | the dot, headlines on dark |
| Muted text | `#C2CCDA` | secondary text on dark (feature graphic tagline) |

The cyan is the only accent. Do not introduce a second one.

### Clear space and minimum size

Keep at least **1/8 of the icon width** free around the card. The bare mark
(`icon-monochrome.svg`) already carries 1/32 of its canvas as clear space.
Minimum practical sizes: card icon **32 px**, bare mark **24 px**. Below that,
use the mark, not the card.

### Do not

- do not round the corners of `icon-square.svg` — Play adds a 30 % radius itself
- do not add shadows or glows; Play adds its own shadow
- do not move the dot off the ring, or mirror/rotate the mark
- do not recolour the dot, or make the ring a gradient
- do not stretch, outline, or place the card on a busy photo without the hairline
- do not use the old gradient-ring/badge artwork anywhere

## Why the launcher card is inset

Android launchers mask adaptive icons to the central **72 of 108 units** and the
mask may be a circle of radius 36. A rounded square of side *s* and corner *r*
survives every mask shape when

```
sqrt(2) * (s/2 - r) + r <= 36
```

The card uses **s = 58, r = 15 → 34.8**, so it stays a rounded square inside
circle, squircle, square and teardrop masks (about 80 % of the visible slot).
A full-bleed square would get its corners shaved off on circle-mask launchers.
The legacy bitmap mipmaps (Android 7 and older, no masking) use the full-bleed
96 % version of the same art.

## Regenerating the bitmaps

`docs/render_assets.py` needs Pillow only (`python3 -m pip install Pillow`) and
mirrors the SVG numbers exactly — the SVGs and the renderer were cross-checked
against each other (mean channel difference below 0.5/255; only edge
antialiasing differs).

```bash
# refresh fastlane/metadata/android/en-US/images/{icon.png,featureGraphic.png}
python3 docs/render_assets.py --font /path/to/Inter.ttf

# icon only, no font needed
python3 docs/render_assets.py --no-feature

# one-off promo exports
python3 docs/render_assets.py --icon-size 1024 --icon-out /tmp/icon-1024.png
python3 docs/render_assets.py --icon-size 1024 --mark --icon-out /tmp/mark.png
python3 docs/render_assets.py --icon-size 512 --square --icon-out /tmp/play.png
```

The feature graphic sets "AoD" and two lines of copy in **Inter** (SIL OFL). The
font is *not* bundled; the script looks for it in `--font`, `$AOD_PROMO_FONT`,
`/tmp/fonts/Inter.ttf`, `~/fonts`, then the system fonts, and falls back to
whatever sans it finds. Inter variable TTF:
<https://raw.githubusercontent.com/google/fonts/main/ofl/inter/Inter%5Bopsz,wght%5D.ttf>

The launcher assets in `app/src/main/res` are the same drawing:
`drawable/ic_launcher_background.xml` (card + hairline),
`drawable-v24/ic_launcher_foreground.xml` (ring + dot),
`drawable/ic_launcher_monochrome.xml`, and the `mipmap-*/ic_launcher*.webp`
bitmaps at 48/72/96/144/192 px.

## Store metadata (fastlane)

```
fastlane/metadata/android/en-US/
├── title.txt                 23 / 30 chars
├── short_description.txt     75 / 80 chars
├── full_description.txt    3398 / 4000 chars
├── changelogs/10100.txt     393 / 500 chars   (named after versionCode)
└── images/
    ├── icon.png             512 × 512, 32-bit PNG, full square
    └── featureGraphic.png  1024 × 500, 24-bit PNG, no alpha
```

Play limits: title 30, short description 80, full description 4000, changelog
500 characters. Icon: 512 × 512, 32-bit PNG, sRGB, ≤ 1024 KB, **full square** —
Play rounds it at 30 % of the icon size and adds the shadow. Feature graphic:
1024 × 500, JPEG or 24-bit PNG.

Upload (needs a Play service-account JSON and an existing listing):

```bash
fastlane supply --package_name com.zoffcc.applications.aod \
                --metadata_path fastlane/metadata/android \
                --skip_upload_apk true --skip_upload_aab true
```

Still missing: **screenshots**. They have to come from a real device, so they
are deliberately not faked here. Capture them into
`fastlane/metadata/android/en-US/images/phoneScreenshots/` as `1.png`, `2.png`, …
(2–8 images, 16:9 or 9:16, min 1080 px on the long side):

```bash
adb exec-out screencap -p > fastlane/metadata/android/en-US/images/phoneScreenshots/1.png
```

Good subjects: the settings screen (high contrast), the app picker with search,
the icon style on a black screen, the camera ring, the edge glow.

To add another language, copy `en-US` to e.g. `de-DE`, translate the four text
files and the changelog; the images can stay as they are (no text in the icon).
