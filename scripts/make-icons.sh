#!/usr/bin/env bash
# Regenerates the Android launcher icons and splash screen from assets/icon-foreground.svg.
# Requires inkscape and ImageMagick (brew install inkscape imagemagick). Run after editing the SVG,
# then `npm run sync`. Background colour is BG below (also written to values/ic_launcher_background.xml).
set -euo pipefail
cd "$(dirname "$0")/.."

BG="#161311"
RES=android/app/src/main/res
FG=assets/icon-foreground.svg
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

# Full-bleed (legacy) art: background + foreground, cropped so the bars fill the icon.
cat > "$tmp/full.svg" <<EOF
<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="72" height="72">
  <rect x="0" y="0" width="108" height="108" fill="$BG"/>
  $(sed -e '1d;$d' "$FG")
</svg>
EOF

for entry in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do   # density:legacy px (portable to bash 3.2)
  d=${entry%:*}; px=${entry#*:}; adaptive=$((px * 108 / 48)); out="$RES/mipmap-$d"
  inkscape "$tmp/full.svg" -o "$tmp/full.png" -w "$px" -h "$px" >/dev/null 2>&1
  magick "$tmp/full.png" \( -size ${px}x${px} xc:none -fill white -draw "roundrectangle 0,0 $((px-1)),$((px-1)) $((px/5)),$((px/5))" \) \
    -compose DstIn -composite "$out/ic_launcher.png"
  magick "$tmp/full.png" \( -size ${px}x${px} xc:none -fill white -draw "circle $((px/2)),$((px/2)) $((px/2)),0" \) \
    -compose DstIn -composite "$out/ic_launcher_round.png"
  inkscape "$FG" -o "$out/ic_launcher_foreground.png" -w "$adaptive" -h "$adaptive" >/dev/null 2>&1
done

cat > "$RES/values/ic_launcher_background.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">$BG</color>
</resources>
EOF

# Splash: solid background with the icon mark centred (replaces the Capacitor logo), at each existing size.
inkscape "$tmp/full.svg" -o "$tmp/mark.png" -w 512 -h 512 >/dev/null 2>&1
for f in "$RES"/drawable*/splash.png; do
  size=$(identify -format "%wx%h" "$f"); w=${size%x*}; h=${size#*x}; m=$(( (w < h ? w : h) * 30 / 100 ))
  magick -size "$size" "xc:$BG" \( "$tmp/mark.png" -resize ${m}x${m} \
    \( +clone -alpha extract -fill black -colorize 100 -fill white -draw "roundrectangle 0,0 $((m-1)),$((m-1)) $((m/5)),$((m/5))" \) \
    -alpha off -compose CopyOpacity -composite \) -gravity center -compose over -composite "$f"
done
echo "icons and splash regenerated"
