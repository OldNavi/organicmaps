#!/usr/bin/env python3
"""Build the automotive-only road-event atlases from their SVG sources."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--skin-generator", type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
assets = root / "android/app/src/auto/assets"
densities = {"mdpi": 18, "hdpi": 27, "xhdpi": 36, "6plus": 43, "xxhdpi": 54, "xxxhdpi": 64}
ET.register_namespace("", "http://www.w3.org/2000/svg")
# Match the normal POI sizes and keep a wider, aspect-correct settlement plate.
variants = {"s": 14, "m": 18, "l": 22}
for theme in ("light", "dark"):
    with tempfile.TemporaryDirectory(prefix="road-vectors-") as directory:
        vectors = Path(directory)
        sources = {path.stem: path for path in (root / "data/styles/road_events" / theme).glob("*.svg")
                   if path.stem != "road-event-settlement-end"}
        for name, stock in {"railway": "railway-crossing-l", "police": "police-m"}.items():
            sources[f"road-event-{name}"] = root / "data/styles/default" / theme / "symbols" / f"{stock}.svg"
        for name, source in sources.items():
            if name == "road-event-coverage":
                shutil.copyfile(source, vectors / source.name)
                continue
            for variant, size in variants.items():
                svg = ET.parse(source).getroot()
                width, height = float(svg.get("width")), float(svg.get("height"))
                limit = size * (1.5 if name == "road-event-settlement-start" else 1)
                factor = limit / max(width, height)
                svg.set("width", f"{width * factor:g}")
                svg.set("height", f"{height * factor:g}")
                ET.ElementTree(svg).write(vectors / f"{name}-{variant}.svg", encoding="unicode")
        for density, size in densities.items():
            with tempfile.TemporaryDirectory(prefix="road-symbols-") as temporary:
                folder = Path(temporary)
                subprocess.run([
                    str(args.skin_generator.resolve()),
                    f"--symbolsDir={vectors}", f"--skinName={folder / 'basic'}", "--skinSuffix=",
                    f"--symbolWidth={size}", f"--symbolHeight={size}",
                ], check=True, env={**os.environ, "QT_QPA_PLATFORM": "offscreen"})
                target = assets / "symbols" / density / theme
                target.mkdir(parents=True, exist_ok=True)
                for suffix in ("png", "xml"):
                    shutil.copyfile(folder / f"symbols.{suffix}", target / f"road-events.{suffix}")
(assets / "additional-symbols.txt").write_text("road-events\n")
