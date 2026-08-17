#!/usr/bin/env python3
"""Packs the US Census Gazetteer into the binary resource bundled by :geocoding-local.

The Gazetteer is public domain, so the packed output is checked in rather than
downloaded at build time. Re-run this only to refresh the vintage:

    python3 scripts/pack-gazetteer.py --year 2025

Format (whole file gzipped), all integers varint-encoded, signed ones zigzagged:

    "LKGEO" magic, 1 byte format version
    varint vintage year
    varint stateCount,  then 2 ASCII bytes per state
    varint lsadCount,   then (varint length, UTF-8 bytes) per LSAD suffix word
    varint placeCount,  then per place, ordered by (normalized name, state):
        varint  shared prefix length with the previous name
        varint  suffix length, then the UTF-8 suffix bytes
        byte    state index
        byte    designation index
        svarint latitude  delta from the previous place, in milli-degrees
        svarint longitude delta from the previous place, in milli-degrees
    varint aliasCount,  then per alias:
        varint  name length, then the UTF-8 name bytes
        varint  index of the place it resolves to
    varint zctaCount,   then per ZCTA, ordered by ZIP:
        varint  ZIP delta from the previous ZIP
        svarint latitude  delta, in milli-degrees
        svarint longitude delta, in milli-degrees

Milli-degrees (~111 m) is deliberate: every coordinate here is the centroid of a
ZIP or place *area*, so finer precision would encode noise, and the deltas shrink
enough to keep the whole bundle small.
"""
import argparse, gzip, io, os, sys, unicodedata, urllib.request, zipfile

MAGIC = b"LKGEO"
VERSION = 1
SCALE = 1000  # milli-degrees

BASE = "https://www2.census.gov/geo/docs/maps-data/data/gazetteer"


def varint(n, out):
    if n < 0:
        raise ValueError(f"varint cannot encode {n}")
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return


def svarint(n, out):
    varint((n << 1) ^ (n >> 63) if n < 0 else (n << 1), out)


def normalize(name):
    """Match the Kotlin decoder's normalization: lowercase, diacritics stripped,
    anything that is not a letter or digit collapsed to a single space."""
    decomposed = unicodedata.normalize("NFD", name.lower())
    stripped = "".join(c for c in decomposed if unicodedata.category(c) != "Mn")
    out = []
    for c in stripped:
        if c.isalnum():
            out.append(c)
        elif out and out[-1] != " ":
            out.append(" ")
    return "".join(out).strip()


def fetch(year, kind, cache_dir):
    name = f"{year}_Gaz_{kind}_national"
    txt = os.path.join(cache_dir, name + ".txt")
    if not os.path.exists(txt):
        url = f"{BASE}/{year}_Gazetteer/{name}.zip"
        print(f"downloading {url}")
        with urllib.request.urlopen(url) as r:
            data = r.read()
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            member = next(n for n in z.namelist() if n.endswith(".txt"))
            os.makedirs(cache_dir, exist_ok=True)
            with open(txt, "wb") as f:
                f.write(z.read(member))
    with open(txt, encoding="utf-8") as f:
        header = next(f)
        sep = "\t" if "\t" in header else "|"
        cols = [c.strip() for c in header.rstrip("\n").split(sep)]
        for line in f:
            parts = line.rstrip("\n").split(sep)
            if len(parts) != len(cols):
                continue
            yield {c: p.strip() for c, p in zip(cols, parts)}


# Consolidated city-county governments. The Gazetteer names these after the merged
# entity rather than the city ("Augusta-Richmond County consolidated government
# (balance)"), which would leave Indianapolis, Nashville, Louisville, Lexington,
# Augusta, Athens and Macon unfindable. They get cleaned names plus a search alias.
CONSOLIDATED_LSADS = {"00", "CG", "CN", "MG", "UC", "UG"}


def clean_consolidated(name):
    """'Nashville-Davidson metropolitan government (balance)' -> 'Nashville-Davidson'.

    Trailing designation words are lowercase in this dataset while genuine name words
    are capitalized, which separates 'Indianapolis city' (designation) from
    'Carson City' (name) without a hardcoded list.
    """
    if name.endswith(" (balance)"):
        name = name[: -len(" (balance)")]
    words = name.split(" ")
    while len(words) > 1 and words[-1][:1].islower():
        words.pop()
    return " ".join(words)


def alias_for(name):
    """The city half of a consolidated name: 'Macon-Bibb County' -> 'Macon'.

    Returns None when there is nothing to split, e.g. 'Carson City' or 'Princeton'.
    """
    for sep in ("-", "/", ","):
        if sep in name:
            head = name.split(sep)[0].strip()
            return head if head and head != name else None
    return None


def load_places(year, cache_dir):
    """Returns ((state, bare name, designation, lat, lon) tuples, alias list).

    NAME carries the legal/statistical designation already ("Abbeville city",
    "Aberdeen CDP"). Splitting it back out both saves space and lets "Abbeville, AL"
    match. The designation is not hardcoded: it is the longest whole-word suffix
    shared by every name carrying that LSAD code, so a new vintage that adds a code
    is handled without touching this script.
    """
    rows = list(fetch(year, "place", cache_dir))

    by_lsad = {}
    for row in rows:
        if row["LSAD"] in CONSOLIDATED_LSADS:
            continue
        by_lsad.setdefault(row["LSAD"], []).append(row["NAME"].split(" "))
    suffixes = {}
    for lsad, names in by_lsad.items():
        shared = names[0][:]
        for words in names[1:]:
            keep = 0
            while keep < len(shared) and keep < len(words) - 1 and shared[len(shared) - 1 - keep] == words[len(words) - 1 - keep]:
                keep += 1
            shared = shared[len(shared) - keep:] if keep else []
            if not shared:
                break
        suffixes[lsad] = " ".join(shared)

    out, aliases = [], []
    for row in rows:
        name, lsad = row["NAME"], row["LSAD"]
        if lsad in CONSOLIDATED_LSADS:
            bare, suffix = clean_consolidated(name), ""
            extra = alias_for(bare)
            if extra:
                aliases.append((extra, row["USPS"], bare))
        else:
            suffix = suffixes[lsad]
            bare = name[: len(name) - len(suffix)].strip() if suffix and name.endswith(suffix) else name
            if not bare:  # a place actually named e.g. "City" would strip to nothing
                bare, suffix = name, ""
        out.append((row["USPS"], bare, suffix, float(row["INTPTLAT"]), float(row["INTPTLONG"])))
    return out, aliases


def load_zctas(year, cache_dir):
    out = []
    for row in fetch(year, "zcta", cache_dir):
        out.append((row["GEOID"], float(row["INTPTLAT"]), float(row["INTPTLONG"])))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--year", type=int, default=2025)
    ap.add_argument("--cache-dir", default="tmp/geodata")
    ap.add_argument(
        "--out",
        default="geocoding-local/src/main/resources/com/lightningkite/services/geocoding/local/us-gazetteer.bin.gz",
    )
    args = ap.parse_args()

    places, aliases = load_places(args.year, args.cache_dir)
    zctas = load_zctas(args.year, args.cache_dir)
    print(f"loaded {len(places)} places, {len(zctas)} ZCTAs, {len(aliases)} aliases")

    # Front-coding shares a prefix length measured in code points; Kotlin measures in
    # UTF-16 code units. Those agree only below U+10000, so assert rather than assume.
    for _, name, _, _, _ in places:
        if any(ord(c) > 0xFFFF for c in name):
            raise SystemExit(f"non-BMP character in place name {name!r}; front-coding would desync")
    if not all(len(z[0]) == 5 and z[0].isdigit() for z in zctas):
        raise SystemExit("ZCTA GEOIDs must be 5 digits to be stored as integers")

    states = sorted({p[0] for p in places})
    designations = sorted({p[2] for p in places})
    if len(states) > 255 or len(designations) > 255:
        raise SystemExit("state/designation tables must fit in a byte")
    state_ix = {s: i for i, s in enumerate(states)}
    designation_ix = {s: i for i, s in enumerate(designations)}
    print(f"designations: {designations}")

    out = bytearray(MAGIC)
    out.append(VERSION)
    varint(args.year, out)

    varint(len(states), out)
    for s in states:
        out += s.encode("ascii")

    varint(len(designations), out)
    for s in designations:
        b = s.encode("utf-8")
        varint(len(b), out)
        out += b

    # Sorting by normalized name globally collapses the ~5800 duplicated names
    # ("Franklin city" appears in 16 states) into shared front-coded prefixes,
    # and lets the decoder binary-search names without building a hash index.
    places.sort(key=lambda p: (normalize(p[1]), p[0]))
    varint(len(places), out)
    prev_name = ""
    plat = plon = 0
    for state, name, designation, lat, lon in places:
        common = 0
        limit = min(len(prev_name), len(name), 127)
        while common < limit and prev_name[common] == name[common]:
            common += 1
        rest = name[common:].encode("utf-8")
        varint(common, out)
        varint(len(rest), out)
        out += rest
        out.append(state_ix[state])
        out.append(designation_ix[designation])
        qlat, qlon = round(lat * SCALE), round(lon * SCALE)
        svarint(qlat - plat, out)
        svarint(qlon - plon, out)
        plat, plon = qlat, qlon
        prev_name = name

    # Aliases point at a place already emitted above rather than repeating its
    # coordinates, so reverse geocoding never sees the same point twice.
    place_ix = {(p[0], p[1]): i for i, p in enumerate(places)}
    varint(len(aliases), out)
    for alias, state, target in aliases:
        b = alias.encode("utf-8")
        varint(len(b), out)
        out += b
        varint(place_ix[(state, target)], out)

    zctas.sort()
    varint(len(zctas), out)
    pzip = plat = plon = 0
    for zip_code, lat, lon in zctas:
        z = int(zip_code)
        varint(z - pzip, out)
        pzip = z
        qlat, qlon = round(lat * SCALE), round(lon * SCALE)
        svarint(qlat - plat, out)
        svarint(qlon - plon, out)
        plat, plon = qlat, qlon

    packed = gzip.compress(bytes(out), 9)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "wb") as f:
        f.write(packed)
    print(f"raw={len(out)/1024:.0f}KB gzip={len(packed)/1024:.0f}KB -> {args.out}")


if __name__ == "__main__":
    main()
