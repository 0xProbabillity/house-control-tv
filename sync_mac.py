#!/usr/bin/env python3
"""One-way settings sync over the paired TV's LAN API. Never sends device state/commands.

Besides the plan, the Mac app's Home Assistant and Hue connections (address + token / bridge key + certificate pin,
the same ones it shares with a paired iPhone) are passed on so the TV can control those devices with the Mac off.
Set HOUSE_TV_SYNC_CONNECTIONS=0 to keep them on the Mac. Smart Life sessions are never read or sent."""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent
HOUSE = Path.home()/'Library/Application Support/HouseControl'
PLAN = HOUSE/'plan.json'
# The Mac app's own files (FileSecretStore, mode 600) and the fields the TV needs from each.
CONNECTIONS = {'homeassistant': ('url', 'token', 'name', 'version'), 'hue': ('host', 'key', 'bridgeId', 'name', 'certificate')}
LOCAL = ROOT/'.local'
LABEL = 'dev.housecontrol.tv.settings-sync'
AGENT = Path.home()/'Library/LaunchAgents'/f'{LABEL}.plist'


def fingerprint(plan):
    if not isinstance(plan, dict) or not isinstance(plan.get('rooms'), list) or not plan['rooms']:
        raise ValueError('Save a valid home in House Control first.')
    return hashlib.sha256(json.dumps(plan, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def connections(directory=HOUSE):
    """The Mac's Home Assistant and Hue connections; None for one that is not set up (the TV then drops the Mac's copy)."""
    if os.environ.get('HOUSE_TV_SYNC_CONNECTIONS') == '0':
        return {}
    out = {}
    for name, fields in CONNECTIONS.items():
        try:
            data = json.loads((directory/f'{name}.json').read_text())
        except FileNotFoundError:
            out[name] = None
            continue
        if not isinstance(data, dict):
            raise ValueError(f'Unreadable {name} connection.')
        out[name] = {key: data[key] for key in fields if isinstance(data.get(key), str)}
    return out


def connections_fingerprint(shared):
    return hashlib.sha256(json.dumps(shared, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def atomic_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(data, indent=2)+'\n')
    temp.chmod(0o600)
    temp.replace(path)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class SyncClient:
    def __init__(self, config):
        parsed = urlsplit(config['endpoint'])
        address = ipaddress.ip_address(parsed.hostname)
        if parsed.scheme != 'http' or parsed.port != 8788 or not address.is_private or parsed.username or parsed.path not in ('', '/'):
            raise ValueError('Expected the paired TV on the private LAN, port 8788.')
        self.endpoint = config['endpoint'].rstrip('/')
        self.token = config['token']
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, path, body=None):
        request = urllib.request.Request(self.endpoint+path,
            data=None if body is None else json.dumps(body, allow_nan=False).encode(),
            headers={'Authorization': 'Bearer '+self.token, 'Content-Type': 'application/json'})
        with self.opener.open(request, timeout=5) as response:
            result = json.load(response)
        if result.get('error'):
            raise RuntimeError('TV rejected the settings update.')
        return result

    def sync(self, plan):
        digest = fingerprint(plan)
        current = self.request('/api/home')
        if current.get('syncFingerprint') == digest:
            return False
        result = self.request('/api/import', {'plan': plan, 'settingsOnly': True, 'syncFingerprint': digest})
        if result.get('home', {}).get('syncFingerprint') != digest:
            raise RuntimeError('Update House Control TV to support automatic settings sync.')
        return True

    def sync_connections(self, shared):
        """Sends the Mac's connections when they changed since the TV last took them. Never logs them."""
        if not shared:
            return False
        digest = connections_fingerprint(shared)
        if self.request('/api/connections').get('macFingerprint') == digest:
            return False
        result = self.request('/api/connections/adopt', dict(shared, fingerprint=digest))
        if result.get('macFingerprint') != digest:
            raise RuntimeError('Update House Control TV to receive Home Assistant and Hue connections.')
        return True


def discover():
    """Finds the paired TV again after its address changed: by the serial number saved when pairing, over ADB's mDNS.
    Discovery only: never launches an Activity or transfers the plan through ADB."""
    path = LOCAL/'tv-direct.json'
    config = json.loads(path.read_text())
    serial = config.get('serial', '')
    if not serial:
        return
    services = subprocess.run(['adb', 'mdns', 'services'], capture_output=True, text=True, timeout=5).stdout
    for line in services.splitlines():
        fields = line.split()
        if len(fields) >= 3 and fields[0] == 'adb-'+serial and fields[1] == '_adb._tcp':
            device = fields[2]
            config.update(device=device, endpoint='http://'+device.split(':')[0]+':8788')
            atomic_json(path, config)
            return


def report(status, digest='', message=''):
    data = {'status': status, 'fingerprint': digest, 'message': message, 'updatedAt': time.time()}
    atomic_json(LOCAL/'settings-sync-status.json', data)


def watch():
    stopped = threading.Event()
    import signal
    signal.signal(signal.SIGTERM, lambda *_: stopped.set())
    signal.signal(signal.SIGINT, lambda *_: stopped.set())
    candidate = ''
    next_check = 0
    failures = 0
    last_discovery = 0
    while not stopped.is_set():
        digest = ''
        try:
            plan = json.loads(PLAN.read_text())
            digest = fingerprint(plan)
            if digest != candidate:
                candidate = digest
                next_check = 0  # One quiet interval before sending an atomic editor save.
            elif time.monotonic() >= next_check:
                config = json.loads((LOCAL/'tv-direct.json').read_text())
                client = SyncClient(config)
                changed = client.sync(plan)
                try:
                    changed |= client.sync_connections(connections())
                    note = ''
                except Exception as error:  # an older TV app: the plan still syncs
                    note = ' Home Assistant/Hue connections pending: '+type(error).__name__
                if changed or failures or note or not (LOCAL/'settings-sync-status.json').exists():
                    report('synced', digest, 'Settings saved on TV. Device control remains independent.'+note)
                failures = 0
                next_check = time.monotonic()+20
        except Exception as error:
            failures += 1
            # Keep details/keys/provider IDs out of logs. Latest local settings remain pending.
            report('pending', digest, 'TV unavailable or settings unreadable; retrying automatically. '+type(error).__name__)
            next_check = time.monotonic()+min(60, 2**min(failures, 5))
            if time.monotonic()-last_discovery > 60:
                last_discovery = time.monotonic()
                try: discover()
                except Exception: pass
        stopped.wait(2)


def control(action):
    domain = f'gui/{os.getuid()}'
    if action == 'install':
        LOCAL.mkdir(exist_ok=True)
        if not (LOCAL/'tv-direct.json').exists():
            raise SystemExit('Pair this Mac with the TV first: ./tv.sh pair')
        AGENT.parent.mkdir(parents=True, exist_ok=True)
        AGENT.write_bytes(plistlib.dumps({'Label': LABEL,
            'ProgramArguments': [shutil.which('python3') or sys.executable, str(Path(__file__).resolve()), 'watch'],
            'WorkingDirectory': str(ROOT), 'EnvironmentVariables': {'PATH': '/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin', **({'HOUSE_TV_SYNC_CONNECTIONS': '0'} if os.environ.get('HOUSE_TV_SYNC_CONNECTIONS') == '0' else {})},
            'RunAtLoad': True, 'KeepAlive': True, 'ThrottleInterval': 10,
            'StandardOutPath': str(LOCAL/'settings-sync.log'), 'StandardErrorPath': str(LOCAL/'settings-sync-error.log')}))
        AGENT.chmod(0o600)
        subprocess.run(['launchctl', 'bootout', domain, str(AGENT)], capture_output=True)
        subprocess.run(['launchctl', 'bootstrap', domain, str(AGENT)], check=True, capture_output=True)
        print('Automatic Mac → TV settings sync installed and started.')
    elif action == 'stop':
        subprocess.run(['launchctl', 'bootout', domain, str(AGENT)], capture_output=True)
        print('Automatic settings sync stopped. TV device control is unchanged.')
    elif action == 'status':
        result = subprocess.run(['launchctl', 'print', domain+'/'+LABEL], capture_output=True, text=True)
        print('Running' if result.returncode == 0 and 'state = running' in result.stdout else 'Stopped')
        path = LOCAL/'settings-sync-status.json'
        if path.exists(): print(path.read_text())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['once', 'watch', 'install', 'stop', 'status'], default='once', nargs='?')
    args = parser.parse_args()
    if args.action == 'watch': watch()
    elif args.action == 'once':
        plan = json.loads(PLAN.read_text())
        client = SyncClient(json.loads((LOCAL/'tv-direct.json').read_text()))
        changed = client.sync(plan)
        if client.sync_connections(connections()):
            print('Home Assistant / Hue connections sent to TV.')
        report('synced', fingerprint(plan), 'Settings saved on TV.')
        print('Settings synced directly to TV.' if changed else 'TV settings are already current.')
    else: control(args.action)

if __name__ == '__main__': main()
