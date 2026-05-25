#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SVG="$SCRIPT_DIR/pearlnode-icon.svg"
RES="$SCRIPT_DIR/app/src/main/res"
FDROID="$SCRIPT_DIR/fastlane/metadata/android/en-US/images"

if [ ! -f "$SVG" ]; then
    echo "Error: $SVG not found"
    exit 1
fi

command -v rsvg-convert >/dev/null 2>&1 && RENDERER="rsvg"
command -v inkscape >/dev/null 2>&1 && RENDERER="inkscape"
if [ -z "$RENDERER" ]; then
    echo "Error: install rsvg-convert (librsvg2-bin) or inkscape"
    exit 1
fi

render() {
    local size=$1 out=$2
    if [ "$RENDERER" = "rsvg" ]; then
        rsvg-convert -w "$size" -h "$size" -o "$out" "$SVG"
    else
        inkscape --export-type=png --export-filename="$out" \
                 --export-width="$size" --export-height="$size" "$SVG"
    fi
}

render_padded() {
    local canvas=$1 out=$2
    local icon=$(echo "$canvas * 2 / 3" | bc)
    local tmp
    tmp=$(mktemp /tmp/icon-XXXXXX.png)
    render "$icon" "$tmp"
    convert "$tmp" -background none -gravity center -extent "${canvas}x${canvas}" "$out"
    rm "$tmp"
}

circle_crop() {
    local size=$1 out=$2
    local tmp
    tmp=$(mktemp /tmp/icon-XXXXXX.png)
    render "$size" "$tmp"
    python3 - "$tmp" "$out" "$size" <<'EOF'
import sys
from PIL import Image, ImageDraw

src, dst, size = sys.argv[1], sys.argv[2], int(sys.argv[3])
img = Image.open(src).convert("RGBA")
mask = Image.new("L", (size, size), 0)
ImageDraw.Draw(mask).ellipse((0, 0, size, size), fill=255)
img.putalpha(mask)
img.save(dst)
EOF
    rm "$tmp"
}

echo "Generating legacy square icons..."
for pair in "48:mipmap-mdpi" "72:mipmap-hdpi" "96:mipmap-xhdpi" "144:mipmap-xxhdpi" "192:mipmap-xxxhdpi"; do
    size="${pair%%:*}"; dir="${pair##*:}"
    render "$size" "$RES/$dir/ic_launcher.png"
    echo "  $dir/ic_launcher.png"
done

echo "Generating legacy round icons..."
for pair in "48:mipmap-mdpi" "72:mipmap-hdpi" "96:mipmap-xhdpi" "144:mipmap-xxhdpi" "192:mipmap-xxxhdpi"; do
    size="${pair%%:*}"; dir="${pair##*:}"
    circle_crop "$size" "$RES/$dir/ic_launcher_round.png"
    echo "  $dir/ic_launcher_round.png"
done

echo "Generating adaptive foreground icons..."
for pair in "108:mipmap-mdpi" "162:mipmap-hdpi" "216:mipmap-xhdpi" "324:mipmap-xxhdpi" "432:mipmap-xxxhdpi"; do
    canvas="${pair%%:*}"; dir="${pair##*:}"
    render_padded "$canvas" "$RES/$dir/ic_launcher_foreground.png"
    echo "  $dir/ic_launcher_foreground.png"
done

echo "Generating F-Droid store icon..."
render 512 "$FDROID/icon.png"
echo "  fastlane/.../icon.png"

echo "Generating GitHub README icon..."
mkdir -p "$SCRIPT_DIR/assets"
render 256 "$SCRIPT_DIR/assets/icon.png"
echo "  assets/icon.png"

echo ""
echo "Done. Run 'git add app/src/main/res fastlane assets' to stage the icons."
