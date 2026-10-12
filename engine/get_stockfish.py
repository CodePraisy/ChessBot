"""Downloads the latest official Stockfish into engine/stockfish/ so the game and strengthTest can use it.

  python engine/get_stockfish.py                  # AVX2 build (most CPUs since ~2015, including Ryzen)
  python engine/get_stockfish.py --variant x86-64 # oldest/most compatible build, if the AVX2 one crashes

Stockfish is free software (GPL-3.0) from https://stockfishchess.org; this only fetches the official
GitHub release. Restart the game afterwards: "Stockfish 1320" ... "Stockfish full strength" appear in the
AI menus, and full-strength Stockfish becomes the Review analyser.
"""
from __future__ import annotations

import argparse
import io
import json
import os
import platform
import shutil
import stat
import tarfile
import urllib.request
import zipfile
from pathlib import Path

RELEASES = "https://api.github.com/repos/official-stockfish/Stockfish/releases/latest"
OUT = Path(__file__).resolve().parent / "stockfish"


def asset_name(variant: str) -> str:
    system = platform.system()
    if system == "Windows":
        return f"stockfish-windows-x86-64-{variant}.zip" if variant != "x86-64" else "stockfish-windows-x86-64.zip"
    if system == "Darwin":
        return "stockfish-macos-m1-apple-silicon.tar" if platform.machine() == "arm64" else f"stockfish-macos-x86-64-{variant}.tar"
    return f"stockfish-ubuntu-x86-64-{variant}.tar" if variant != "x86-64" else "stockfish-ubuntu-x86-64.tar"


def get(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "ChessBot", "Accept": "application/octet-stream"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--variant", default="avx2", help="avx2 (default), bmi2, x86-64 ... see the release page")
    args = p.parse_args(argv)

    release = json.loads(get(RELEASES))
    wanted = asset_name(args.variant)
    asset = next((a for a in release["assets"] if a["name"] == wanted), None)
    if asset is None:
        names = ", ".join(a["name"] for a in release["assets"])
        raise SystemExit(f"No {wanted} in Stockfish {release['tag_name']}. Available: {names}")
    print(f"Downloading {wanted} ({asset['size'] / 1e6:.0f} MB) from Stockfish {release['tag_name']}...")
    data = get(asset["browser_download_url"])

    if wanted.endswith(".zip"):
        archive = zipfile.ZipFile(io.BytesIO(data))
        members = [(n, lambda n=n: archive.read(n)) for n in archive.namelist()]
    else:
        archive = tarfile.open(fileobj=io.BytesIO(data))
        members = [(m.name, lambda m=m: archive.extractfile(m).read()) for m in archive.getmembers() if m.isfile()]
    exe = next(((n, read) for n, read in members
                if Path(n).name.startswith("stockfish-") and "/src/" not in n and not n.endswith((".md", ".txt"))), None)
    if exe is None:
        raise SystemExit("Could not find the Stockfish program inside the download")

    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    target = OUT / ("stockfish.exe" if platform.system() == "Windows" else "stockfish")
    target.write_bytes(exe[1]())
    target.chmod(target.stat().st_mode | stat.S_IEXEC)
    (OUT / "VERSION.txt").write_text(f"{release['tag_name']} {wanted}\n")
    print(f"Saved {target}. Restart the game to play Stockfish.")


if __name__ == "__main__":
    main()
