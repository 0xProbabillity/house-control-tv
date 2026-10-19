#!/usr/bin/env python3
"""Open the private companion hosted on the TV, without printing its key."""
import json, subprocess
from pathlib import Path
from urllib.parse import quote

root = Path(__file__).resolve().parents[1]
config = root/'.local/tv-direct.json'
if not config.exists():
    raise SystemExit('Pair this Mac with the TV first: ./tv.sh pair')
data = json.loads(config.read_text())
subprocess.run(['open', data['endpoint']+'/remote#key='+quote(data['token'], safe='')], check=True)
