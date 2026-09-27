"""Builds app/src/main/assets/places.tsv: an offline town list for naming where photos were taken.

Source: GeoNames cities5000 (https://download.geonames.org/export/dump/cities5000.zip), licensed
CC BY 4.0 (https://www.geonames.org). Keeps every Indian town of 5,000+ people and cities of 50,000+
elsewhere. The phone looks up the nearest one; nothing leaves the device.

Usage: .venv/bin/python tools/places.py path/to/cities5000.txt
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
HOME_COUNTRY = "IN"
WORLD_MIN_POP = 50_000


def main(src: str) -> None:
    rows = []
    for line in open(src, encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        name, lat, lon, country, pop = f[1], float(f[4]), float(f[5]), f[8], int(f[14] or 0)
        if country == HOME_COUNTRY or pop >= WORLD_MIN_POP:
            rows.append(f"{name}\t{lat:.4f}\t{lon:.4f}\t{pop}")
    target = ROOT / "app/src/main/assets/places.tsv"
    header = "# GeoNames cities5000 subset, CC BY 4.0 (geonames.org). name\tlat\tlon\tpopulation\n"
    target.write_text(header + "\n".join(rows) + "\n", encoding="utf-8")
    print(f"wrote {target}: {len(rows)} places, {target.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main(sys.argv[1])
