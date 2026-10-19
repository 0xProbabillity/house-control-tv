#!/usr/bin/env python3
"""A pretend Home Assistant and Philips Hue Bridge for testing the TV's local hub support without hardware.

Standard library only (plus the openssl command for the bridge's self-signed certificate). Every command the TV
sends is printed. Nothing here talks to a real device.

  python3 tools/mock_hubs.py --host 0.0.0.0            # then connect the TV to http://<this Mac>:18123
  python3 tools/mock_hubs.py --auto                    # the Hue link button is "pressed" for every pairing attempt

Home Assistant (http, port 18123): /api/config, /api/states[/<id>], /api/services/<domain>/<service>, and
/api/websocket with auth, subscribe_entities and subscribe_events. Hue (https, port 18443): POST /api pairing,
/clip/v2/resource/{bridge,device,light,temperature}, PUT /clip/v2/resource/light/<id>, /eventstream/clip/v2.
Test helpers on both: GET /mock/log, POST /mock/set {"id":…,…}, POST /mock/press (Hue link button).
"""
import argparse
import base64
import hashlib
import json
import queue
import secrets
import ssl
import struct
import subprocess
import shutil
import os
import tempfile
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

LOCK = threading.Lock()
LOG = []


def record(kind, text):
    with LOCK:
        LOG.append({'hub': kind, 'command': text, 'at': time.time()})
    print(f'[{kind}] {text}', flush=True)


class Hub:
    """Subscribers get a queue each; changes are pushed to all of them."""
    def __init__(self):
        self.subscribers = []

    def subscribe(self):
        q = queue.Queue()
        with LOCK:
            self.subscribers.append(q)
        return q

    def unsubscribe(self, q):
        with LOCK:
            if q in self.subscribers:
                self.subscribers.remove(q)

    def publish(self, item):
        with LOCK:
            for q in list(self.subscribers):
                q.put(item)


# ---------------------------------------------------------------- Home Assistant

class HomeAssistant(Hub):
    def __init__(self, token, legacy=False):
        super().__init__()
        self.token, self.legacy = token, legacy
        self.states = {
            'light.living_lamp': ('on', {'friendly_name': 'Living lamp', 'brightness': 128, 'color_mode': 'color_temp',
                                         'color_temp_kelvin': 3000, 'rgb_color': [255, 180, 107],
                                         'supported_color_modes': ['color_temp', 'hs']}),
            'switch.desk_plug': ('off', {'friendly_name': 'Desk plug'}),
            'climate.bedroom_ac': ('cool', {'friendly_name': 'Bedroom AC', 'hvac_modes': ['off', 'cool', 'heat', 'fan_only', 'heat_cool'],
                                            'fan_modes': ['Auto', 'Low', 'Mid', 'High'], 'fan_mode': 'Auto', 'temperature': 23,
                                            'current_temperature': 24.5, 'min_temp': 16, 'max_temp': 30}),
            'sensor.balcony_temperature': ('68.0', {'friendly_name': 'Balcony', 'device_class': 'temperature', 'unit_of_measurement': '°F'}),
        }

    def state(self, entity):
        value, attributes = self.states[entity]
        return {'entity_id': entity, 'state': value, 'attributes': attributes, 'last_changed': time.strftime('%Y-%m-%dT%H:%M:%S+00:00')}

    def change(self, entity, value=None, **attributes):
        with LOCK:
            old, attrs = self.states[entity]
            attrs = dict(attrs, **attributes)
            self.states[entity] = (old if value is None else value, attrs)
        self.publish(entity)

    def call(self, domain, service, data):
        entity = data.get('entity_id', '')
        if entity not in self.states or not entity.startswith(domain + '.'):
            return 400
        record('homeassistant', f'{domain}.{service} {json.dumps({k: v for k, v in data.items()}, sort_keys=True)}')
        if service in ('turn_on', 'turn_off'):
            extra = {}
            if 'brightness_pct' in data:
                extra['brightness'] = round(data['brightness_pct'] * 2.55)
            if 'color_temp_kelvin' in data:
                extra.update(color_mode='color_temp', color_temp_kelvin=data['color_temp_kelvin'])
            if 'rgb_color' in data:
                extra.update(color_mode='hs', rgb_color=data['rgb_color'])
            self.change(entity, 'on' if service == 'turn_on' else 'off', **extra)
        elif service == 'set_hvac_mode':
            self.change(entity, data['hvac_mode'])
        elif service == 'set_temperature':
            self.change(entity, None, temperature=data['temperature'])
        elif service == 'set_fan_mode':
            self.change(entity, None, fan_mode=data['fan_mode'])
        else:
            return 400
        return 200


def ws_read(rfile):
    head = rfile.read(2)
    if len(head) < 2:
        raise EOFError
    opcode, length = head[0] & 15, head[1] & 127
    if length == 126:
        length = struct.unpack('>H', rfile.read(2))[0]
    elif length == 127:
        length = struct.unpack('>Q', rfile.read(8))[0]
    mask = rfile.read(4) if head[1] & 128 else b'\0\0\0\0'
    data = bytes(b ^ mask[i % 4] for i, b in enumerate(rfile.read(length)))
    if opcode == 8:
        raise EOFError
    return data.decode()


def ws_send(wfile, text):
    data = text.encode()
    header = bytes([0x81]) + (bytes([len(data)]) if len(data) < 126 else bytes([126]) + struct.pack('>H', len(data)) if len(data) < 65536 else bytes([127]) + struct.pack('>Q', len(data)))
    wfile.write(header + data)
    wfile.flush()


class HAHandler(BaseHTTPRequestHandler):
    hub = None
    protocol_version = 'HTTP/1.1'  # as Home Assistant: the WebSocket upgrade answers "HTTP/1.1 101"

    def log_message(self, *args):
        pass

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def authorized(self):
        if self.headers.get('Authorization') != 'Bearer ' + self.hub.token:
            self.reply(401, {'message': 'Invalid token'})
            return False
        return True

    def body(self):
        length = int(self.headers.get('Content-Length') or 0)
        return json.loads(self.rfile.read(length) or b'{}')

    def do_GET(self):
        if self.path == '/mock/log':
            return self.reply(200, LOG)
        if self.path == '/api/websocket' and self.headers.get('Upgrade', '').lower() == 'websocket':
            return self.websocket()
        if not self.authorized():
            return
        if self.path == '/api/config':
            return self.reply(200, {'location_name': 'Mock Home', 'version': '2026.10.0'})
        if self.path == '/api/states':
            return self.reply(200, [self.hub.state(e) for e in self.hub.states])
        if self.path.startswith('/api/states/'):
            entity = self.path[len('/api/states/'):]
            return self.reply(200, self.hub.state(entity)) if entity in self.hub.states else self.reply(404, {'message': 'Entity not found.'})
        self.reply(404, {'message': 'Not found'})

    def do_POST(self):
        if self.path == '/mock/set':
            b = self.body()
            self.hub.change(b.pop('id'), b.pop('state', None), **b)
            return self.reply(200, {'ok': True})
        if not self.authorized():
            return
        parts = self.path.split('/')
        if len(parts) == 5 and parts[1:3] == ['api', 'services']:
            status = self.hub.call(parts[3], parts[4], self.body())
            return self.reply(status, [] if status == 200 else {'message': 'Invalid service call'})
        self.reply(404, {'message': 'Not found'})

    def websocket(self):
        accept = base64.b64encode(hashlib.sha1((self.headers['Sec-WebSocket-Key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
        self.send_response(101)
        self.send_header('Upgrade', 'websocket')
        self.send_header('Connection', 'Upgrade')
        self.send_header('Sec-WebSocket-Accept', accept)
        self.end_headers()
        self.wfile.flush()
        hub, out = self.hub, self.hub.subscribe()
        send_lock = threading.Lock()

        def send(obj):
            with send_lock:
                ws_send(self.wfile, json.dumps(obj))
        try:
            send({'type': 'auth_required', 'ha_version': '2026.10.0'})
            auth = json.loads(ws_read(self.rfile))
            if auth.get('access_token') != hub.token:
                send({'type': 'auth_invalid', 'message': 'Invalid access token'})
                return
            send({'type': 'auth_ok', 'ha_version': '2026.10.0'})
            subscriptions = {}
            stop = threading.Event()

            def pump():
                while not stop.is_set():
                    try:
                        entity = out.get(timeout=.5)
                    except queue.Empty:
                        continue
                    for sid, (kind, ids) in list(subscriptions.items()):
                        if kind == 'entities' and entity in ids:
                            s = hub.state(entity)
                            send({'id': sid, 'type': 'event', 'event': {'c': {entity: {'+': {'s': s['state'], 'a': s['attributes']}}}}})
                        elif kind == 'events':
                            send({'id': sid, 'type': 'event', 'event': {'event_type': 'state_changed', 'data': {'entity_id': entity, 'new_state': hub.state(entity)}}})
            threading.Thread(target=pump, daemon=True).start()
            try:
                while True:
                    message = json.loads(ws_read(self.rfile))
                    kind = message.get('type')
                    if kind == 'subscribe_entities' and not hub.legacy:
                        ids = [e for e in message.get('entity_ids', []) if e in hub.states]
                        subscriptions[message['id']] = ('entities', set(ids))
                        send({'id': message['id'], 'type': 'result', 'success': True, 'result': None})
                        send({'id': message['id'], 'type': 'event', 'event': {'a': {e: {'s': hub.state(e)['state']} for e in ids}}})
                        record('homeassistant', f'websocket subscribe_entities {sorted(ids)}')
                    elif kind == 'subscribe_events':
                        subscriptions[message['id']] = ('events', None)
                        send({'id': message['id'], 'type': 'result', 'success': True, 'result': None})
                        record('homeassistant', 'websocket subscribe_events state_changed')
                    else:
                        send({'id': message.get('id'), 'type': 'result', 'success': False, 'error': {'code': 'unknown_command', 'message': 'Unknown command.'}})
            finally:
                stop.set()
        except (EOFError, ConnectionError, OSError, ValueError):
            pass
        finally:
            hub.unsubscribe(out)


# ---------------------------------------------------------------- Philips Hue

class Bridge(Hub):
    def __init__(self, auto=False):
        super().__init__()
        self.auto, self.button_until, self.keys = auto, 0, set()
        self.bridge_device, self.bridge = str(uuid.uuid4()), str(uuid.uuid4())
        self.resources = {'bridge': [{'id': self.bridge, 'type': 'bridge', 'bridge_id': 'mock0bridge0001', 'owner': {'rid': self.bridge_device, 'rtype': 'device'}}],
                          'device': [{'id': self.bridge_device, 'type': 'device', 'metadata': {'name': 'Mock Hue Bridge', 'archetype': 'bridge_v2'}, 'services': [{'rid': self.bridge, 'rtype': 'bridge'}]}],
                          'light': [], 'temperature': []}
        for name, kind in [('Hue color lamp', 'color'), ('Hue white ambiance', 'ambiance'), ('Hue smart plug', 'plug')]:
            light = {'id': str(uuid.uuid4()), 'type': 'light', 'metadata': {'name': name, 'archetype': 'classic_bulb'}, 'on': {'on': False}}
            if kind != 'plug':
                light['dimming'] = {'brightness': 80.0}
            if kind in ('color', 'ambiance'):
                light['color_temperature'] = {'mirek': 366, 'mirek_valid': True}
            if kind == 'color':
                light['color'] = {'xy': {'x': 0.4573, 'y': 0.41}}
            self.resources['light'].append(light)
        self.resources['temperature'].append({'id': str(uuid.uuid4()), 'type': 'temperature', 'temperature': {'temperature': 21.3, 'temperature_report': {'temperature': 21.3}}})

    def find(self, kind, rid):
        return next((r for r in self.resources[kind] if r['id'] == rid), None)

    def apply(self, rid, body):
        light = self.find('light', rid)
        if light is None:
            return False
        record('hue', f'PUT light {light["metadata"]["name"]} {json.dumps(body, sort_keys=True)}')
        with LOCK:
            if 'on' in body:
                light['on'] = {'on': bool(body['on']['on'])}
            if 'dimming' in body and 'dimming' in light:
                light['dimming'] = {'brightness': float(body['dimming']['brightness'])}
            if 'color_temperature' in body and 'color_temperature' in light:
                light['color_temperature'] = {'mirek': int(body['color_temperature']['mirek']), 'mirek_valid': True}
            if 'color' in body and 'color' in light:
                light['color'] = {'xy': body['color']['xy']}
                light['color_temperature']['mirek_valid'] = False
        self.publish({'id': rid, 'type': 'light'})
        return True


class HueHandler(BaseHTTPRequestHandler):
    hub = None

    def log_message(self, *args):
        pass

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def body(self):
        length = int(self.headers.get('Content-Length') or 0)
        return json.loads(self.rfile.read(length) or b'{}')

    def authorized(self):
        if self.headers.get('hue-application-key') not in self.hub.keys:
            self.reply(403, {'errors': [{'description': 'unauthorized user'}]})
            return False
        return True

    def do_GET(self):
        if self.path == '/mock/log':
            return self.reply(200, LOG)
        if not self.authorized():
            return
        if self.path == '/eventstream/clip/v2':
            return self.events()
        parts = self.path.split('/')
        if len(parts) == 5 and parts[1:4] == ['clip', 'v2', 'resource'] and parts[4] in self.hub.resources:
            return self.reply(200, {'errors': [], 'data': self.hub.resources[parts[4]]})
        self.reply(404, {'errors': [{'description': 'not found'}]})

    def do_POST(self):
        if self.path == '/mock/press':
            self.hub.button_until = time.time() + 30
            record('hue', 'link button pressed')
            return self.reply(200, {'ok': True})
        if self.path == '/mock/set':
            b = self.body()
            light = self.hub.find('light', b['id'])
            with LOCK:
                light['on'] = {'on': bool(b['on'])}
            self.hub.publish({'id': b['id'], 'type': 'light'})
            return self.reply(200, {'ok': True})
        if self.path == '/api':
            body = self.body()
            if not (self.hub.auto or time.time() < self.hub.button_until):
                return self.reply(200, [{'error': {'type': 101, 'address': '', 'description': 'link button not pressed'}}])
            key = secrets.token_urlsafe(30)
            self.hub.keys.add(key)
            record('hue', f'paired {body.get("devicetype")}')
            return self.reply(200, [{'success': {'username': key, 'clientkey': secrets.token_hex(16).upper()}}])
        self.reply(404, {'errors': [{'description': 'not found'}]})

    def do_PUT(self):
        if not self.authorized():
            return
        parts = self.path.split('/')
        if len(parts) == 6 and parts[1:5] == ['clip', 'v2', 'resource', 'light'] and self.hub.apply(parts[5], self.body()):
            return self.reply(200, {'errors': [], 'data': [{'rid': parts[5], 'rtype': 'light'}]})
        self.reply(404, {'errors': [{'description': 'not found'}]})

    def events(self):
        out = self.hub.subscribe()
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.end_headers()
        try:
            self.wfile.write(b': hi\n\n')
            self.wfile.flush()
            while True:
                try:
                    item = out.get(timeout=20)
                except queue.Empty:
                    continue
                event = [{'creationtime': time.strftime('%Y-%m-%dT%H:%M:%SZ'), 'id': str(uuid.uuid4()), 'type': 'update', 'data': [item]}]
                self.wfile.write(f'id: {int(time.time())}:0\ndata: {json.dumps(event)}\n\n'.encode())
                self.wfile.flush()
        except (ConnectionError, OSError):
            pass
        finally:
            self.hub.unsubscribe(out)


def certificate(directory):
    """The mock bridge's self-signed certificate. Made in a private folder and renamed into place in one step, so two
    mocks starting together never pair one's key with the other's certificate."""
    directory.mkdir(parents=True, exist_ok=True)
    pair = directory/'pair'
    if not (pair/'cert.pem').exists():
        work = Path(tempfile.mkdtemp(dir=directory))
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1', '-nodes', '-keyout', str(work/'key.pem'),
                        '-out', str(work/'cert.pem'), '-days', '3650', '-subj', '/C=NL/O=Philips Hue/CN=mock0bridge0001'], check=True, capture_output=True)
        try:
            os.rename(work, pair)
        except OSError:
            shutil.rmtree(work, ignore_errors=True)  # another mock got there first; use its pair
    return pair/'cert.pem', pair/'key.pem'


def serve(host, ha_port, hue_port, token, auto, legacy, state_file=None):
    ha, bridge = HomeAssistant(token, legacy), Bridge(auto)
    HAHandler.hub, HueHandler.hub = ha, bridge
    ha_server = ThreadingHTTPServer((host, ha_port), HAHandler)
    hue_server = ThreadingHTTPServer((host, hue_port), HueHandler)
    cert, key = certificate(Path(__file__).resolve().parents[1]/'.local'/'mock-hue')
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(cert, key)
    hue_server.socket = context.wrap_socket(hue_server.socket, server_side=True)
    for server in (ha_server, hue_server):
        server.daemon_threads = True
        threading.Thread(target=server.serve_forever, daemon=True).start()
    info = {'homeassistant': f'http://{host}:{ha_server.server_address[1]}', 'token': token, 'hue': f'{host}:{hue_server.server_address[1]}',
            'lights': {l['metadata']['name']: l['id'] for l in bridge.resources['light']}, 'sensor': bridge.resources['temperature'][0]['id']}
    if state_file:
        Path(state_file).write_text(json.dumps(info, indent=2))
    return info, ha, bridge


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--ha-port', type=int, default=18123)
    parser.add_argument('--hue-port', type=int, default=18443)
    parser.add_argument('--token', default=None, help='Home Assistant token (random when omitted)')
    parser.add_argument('--auto', action='store_true', help='press the Hue link button for every pairing attempt')
    parser.add_argument('--legacy', action='store_true', help='behave like Home Assistant before 2022.4 (no subscribe_entities)')
    parser.add_argument('--state-file', help='write addresses, token and resource ids here as JSON')
    args = parser.parse_args()
    info, _, _ = serve(args.host, args.ha_port, args.hue_port, args.token or secrets.token_urlsafe(32), args.auto, args.legacy, args.state_file)
    print(json.dumps(info, indent=2), flush=True)
    print('Hue link button: press Enter here (or start with --auto).', flush=True)
    try:
        while True:
            input()
            HueHandler.hub.button_until = time.time() + 30
            record('hue', 'link button pressed')
    except (EOFError, KeyboardInterrupt):
        try:
            while True:
                time.sleep(3600)
        except KeyboardInterrupt:
            pass


if __name__ == '__main__':
    main()
