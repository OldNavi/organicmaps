#!/usr/bin/env python3
"""Generate automotive country-code lookup from the map metadata."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[2]
data = json.loads((root / "data/countries_meta.txt").read_text())
result = {name: meta["iso3166-1"]["alpha-2"] for name, meta in data.items()
          if meta.get("iso3166-1", {}).get("alpha-2")}
# Use metadata of individual map regions first: an overseas territory can have
# a different code from its parent group in the download tree.
tree = json.loads((root / "data/countries.json").read_text())
metadata_codes = dict(result)
group_aliases = {"United Kingdom": "UK", "New Zealand": "New Zealand North"}

def populate(node, inherited=None):
    name = node["id"]
    candidates = [name, *node.get("country_name_synonyms", [])]
    if name.endswith(" Region"):
        candidates.append(name.removesuffix(" Region"))
    if name in group_aliases:
        candidates.append(group_aliases[name])
    code = next((metadata_codes[key] for key in candidates if key in metadata_codes), None)
    if code is None:
        matches = [key for key in metadata_codes if name.startswith(key + "_")]
        if matches:
            code = metadata_codes[max(matches, key=len)]
    code = code or inherited
    if code:
        result[name] = code
    for child in node.get("g", []):
        populate(child, code)

populate(tree)

output = root / "android/app/src/auto/assets/road_event_countries.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2) + "\n")
