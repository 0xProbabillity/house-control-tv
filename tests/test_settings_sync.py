"""Contract tests for automatic LAN settings sync; no real home/device access."""
import importlib.util
import json
import unittest
from pathlib import Path

spec=importlib.util.spec_from_file_location('sync_mac',Path(__file__).resolve().parents[1]/'sync_mac.py')
sync=importlib.util.module_from_spec(spec);spec.loader.exec_module(sync)
PLAN={'rooms':[{'id':'living','rect':[0,0,4,4]}],'lights':[],'scenes':[]}

class FakeTV(sync.SyncClient):
 def __init__(self):self.remote='';self.calls=[];self.revision=0;self.fail_after_commit=False
 def request(self,path,body=None):
  self.calls.append((path,body))
  if body is not None:
   self.remote=body['syncFingerprint'];self.revision+=1
   if self.fail_after_commit:self.fail_after_commit=False;raise TimeoutError()
  home={'syncFingerprint':self.remote,'state':{'lights':{'lamp':{'on':True}}}}
  return {'home':home} if body is not None else home

class SettingsSyncTests(unittest.TestCase):
 def test_canonical_hash_ignores_key_order(self):
  self.assertEqual(sync.fingerprint(PLAN),sync.fingerprint(dict(reversed(list(PLAN.items())))))
 def test_first_sync_sends_only_settings(self):
  tv=FakeTV();self.assertTrue(tv.sync(PLAN));body=tv.calls[-1][1]
  self.assertEqual(set(body),{'plan','settingsOnly','syncFingerprint'})
  self.assertTrue(body['settingsOnly']);self.assertNotIn('state',body)
 def test_unchanged_settings_do_not_import_or_reset_scene(self):
  tv=FakeTV();tv.sync(PLAN);self.assertFalse(tv.sync(PLAN));self.assertEqual(tv.revision,1)
 def test_changed_scene_is_delivered(self):
  tv=FakeTV();tv.sync(PLAN)
  self.assertTrue(tv.sync(dict(PLAN,scenes=[{'id':'night','lights':{'bedroom.lamp':{'on':True}}}])))
  self.assertEqual(tv.revision,2)
 def test_lost_response_does_not_repeat_import(self):
  tv=FakeTV();tv.fail_after_commit=True
  with self.assertRaises(TimeoutError):tv.sync(PLAN)
  self.assertFalse(tv.sync(PLAN));self.assertEqual(tv.revision,1)
 def test_tv_reset_reconciles_without_local_edit(self):
  tv=FakeTV();tv.sync(PLAN);tv.remote='';self.assertTrue(tv.sync(PLAN))
 def test_bad_plan_rejected_before_network(self):
  tv=FakeTV()
  with self.assertRaises(ValueError):tv.sync({'rooms':[]})
  self.assertEqual(tv.calls,[])
 def test_pairing_stays_on_private_lan(self):
  for endpoint in ['https://example.com:8788','http://8.8.8.8:8788','http://192.168.1.2:80','http://key@192.168.1.2:8788']:
   with self.assertRaises(ValueError):sync.SyncClient({'endpoint':endpoint,'token':'test'})

class FakeConnectionsTV(sync.SyncClient):
 def __init__(self):self.remote='';self.adopted=[]
 def request(self,path,body=None):
  if path=='/api/connections/adopt':self.adopted.append(body);self.remote=body['fingerprint']
  return {'macFingerprint':self.remote}

class ConnectionSyncTests(unittest.TestCase):
 def setUp(self):
  import tempfile;self.dir=Path(tempfile.mkdtemp())
  (self.dir/'homeassistant.json').write_text(json.dumps({'url':'http://ha.local:8123','token':'secret-token','name':'Home','version':'1','extra':'x'}))
 def test_reads_only_needed_fields_and_missing_hub_is_none(self):
  shared=sync.connections(self.dir)
  self.assertEqual(shared['homeassistant'],{'url':'http://ha.local:8123','token':'secret-token','name':'Home','version':'1'})
  self.assertIsNone(shared['hue'])
 def test_sent_once_until_changed(self):
  tv=FakeConnectionsTV();shared=sync.connections(self.dir)
  self.assertTrue(tv.sync_connections(shared));self.assertFalse(tv.sync_connections(shared));self.assertEqual(len(tv.adopted),1)
  self.assertNotIn('secret-token',tv.adopted[0]['fingerprint'])
  (self.dir/'hue.json').write_text(json.dumps({'host':'192.168.1.2','key':'k'*20,'certificate':'a'*64}))
  self.assertTrue(tv.sync_connections(sync.connections(self.dir)));self.assertEqual(tv.adopted[-1]['hue']['host'],'192.168.1.2')
 def test_opt_out(self):
  import os;os.environ['HOUSE_TV_SYNC_CONNECTIONS']='0'
  try:self.assertEqual(sync.connections(self.dir),{});self.assertFalse(FakeConnectionsTV().sync_connections({}))
  finally:del os.environ['HOUSE_TV_SYNC_CONNECTIONS']

if __name__=='__main__':unittest.main()
