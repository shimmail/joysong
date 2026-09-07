"""Generate iOS icons from the Android brand vector. Requires Pillow.

Run: python tool/generate_app_icons.py
The path reader supports the artwork's absolute M, C and Z commands.
"""

import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android/app/src/main/res"
CATALOG = ROOT / "ios/Runner/Assets.xcassets"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def path_points(data):
    tokens = iter(re.findall(r"[A-Za-z]|-?\d+(?:\.\d+)?", data))
    points = []
    for command in tokens:
        if command == "M":
            current = (float(next(tokens)), float(next(tokens)))
            points.append(current)
        elif command == "C":
            controls = [current] + [
                (float(next(tokens)), float(next(tokens))) for _ in range(3)
            ]
            for step in range(1, 65):
                t = step / 64
                weights = ((1 - t) ** 3, 3 * (1 - t) ** 2 * t,
                           3 * (1 - t) * t * t, t ** 3)
                points.append(tuple(
                    sum(weight * point[axis]
                        for weight, point in zip(weights, controls))
                    for axis in (0, 1)
                ))
            current = controls[-1]
        elif command != "Z":
            raise ValueError(f"Unsupported vector command: {command}")
    return points


def main():
    vector = ET.parse(RES / "drawable/ic_launcher_foreground.xml").getroot()
    colors = ET.parse(RES / "values/colors.xml").getroot()
    background = colors.find("color[@name='ic_launcher_background']").text
    # Remove adaptive overscan for iOS by using the central 72-unit square.
    canvas_size = 2048
    canvas = Image.new("RGB", (canvas_size, canvas_size), background)
    draw = ImageDraw.Draw(canvas)
    for path in vector.findall("path"):
        points = path_points(path.attrib[ANDROID + "pathData"])
        draw.polygon([
            ((x - 18) / 72 * canvas_size, (y - 18) / 72 * canvas_size)
            for x, y in points
        ], fill=path.attrib[ANDROID + "fillColor"])

    output = CATALOG / "AppIcon.appiconset"
    output.mkdir(parents=True, exist_ok=True)
    images = []
    slots = {
        "iphone": [(20, (2, 3)), (29, (2, 3)), (40, (2, 3)), (60, (2, 3))],
        "ipad": [(20, (1, 2)), (29, (1, 2)), (40, (1, 2)),
                 (76, (1, 2)), (83.5, (2,))],
        "ios-marketing": [(1024, (1,))],
    }
    rendered = set()
    for idiom, sizes in slots.items():
        for size, scales in sizes:
            for scale in scales:
                pixels = int(size * scale)
                filename = f"Icon-{pixels}.png"
                if pixels not in rendered:
                    canvas.resize((pixels, pixels), Image.Resampling.LANCZOS).save(
                        output / filename, optimize=True
                    )
                    rendered.add(pixels)
                images.append({"idiom": idiom, "size": f"{size}x{size}",
                               "scale": f"{scale}x", "filename": filename})
    info = {"version": 1, "author": "xcode"}
    for target, data in [(CATALOG / "Contents.json", {"info": info}),
                         (output / "Contents.json", {"images": images, "info": info})]:
        target.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {len(rendered)} opaque PNGs for {len(images)} iOS icon slots.")


if __name__ == "__main__":
    main()
