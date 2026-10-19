#!/usr/bin/env python3
"""One-time floor-plan/state import to TV. Does not copy any sign-in credentials."""
import argparse,base64,json,subprocess,sys,secrets,time,urllib.request,urllib.error
from pathlib import Path
ROOT=Path(__file__).resolve().parent

def main():
 p=argparse.ArgumentParser();p.add_argument('--device',required=True,help='the TV adb address (192.168.1.50:5555) or serial');p.add_argument('--install',action='store_true');args=p.parse_args();device=args.device
 raw=json.loads((Path.home()/'Library/Application Support/HouseControl/plan.json').read_text())
 # The Mac app's widget snapshot seeds the TV with the current device states, when there is one.
 widgets=Path.home()/'Library/Group Containers/io.github.0xprobabillity.HouseControl/Widgets/home.json'
 snap=json.loads(widgets.read_text()) if widgets.exists() else {'lights':[],'acs':[]};state={'lights':{},'acs':{},'scene':snap.get('scene')}
 for section,keys in [('lights',['on','brightness','temp','color']),('acs',['on','target','mode','fan','roomTemp','swing','sleep','eco'])]:
  for d in snap[section]:state[section][d['id']]={k:d[k] for k in keys if k in d}
 # Only the non-secret User Code is copied. TV gets its own login through Smart Life QR approval.
 account=Path.home()/'Library/Application Support/HouseControl/smartlife.json';user_code=json.loads(account.read_text()).get('userCode','') if account.exists() else ''
 payload={'plan':raw,'state':state,'userCode':user_code}
 if args.install:subprocess.run(['adb','-s',device,'install','-r',str(ROOT/'android/dist/house-control-tv.apk')],check=True,capture_output=True)
 local=ROOT/'.local';local.mkdir(exist_ok=True);keyfile=local/'tv-direct.json'
 direct=json.loads(keyfile.read_text()) if keyfile.exists() else {'token':secrets.token_urlsafe(32)}
 direct['device']=device;direct['endpoint']='http://'+device.split(':')[0]+':8788'
 direct['serial']=subprocess.run(['adb','-s',device,'shell','getprop','ro.serialno'],capture_output=True,text=True).stdout.strip() # finds the TV again if its address changes;keyfile.write_text(json.dumps(direct,indent=2));keyfile.chmod(0o600)
 def read_tv():
  request=urllib.request.Request(direct['endpoint']+'/api/home',headers={'Authorization':'Bearer '+direct['token']})
  with urllib.request.urlopen(request,timeout=2) as response:return json.load(response)
 try:previous=read_tv().get('revision')
 except Exception:previous=None
 encoded=base64.urlsafe_b64encode(json.dumps(payload,separators=(',',':')).encode()).decode()
 result=subprocess.run(['adb','-s',device,'shell','am','start','-n','dev.housecontrol.tv/.MainActivity','--es','homeImport',encoded,'--es','remoteKey',direct['token'],'--es','provisionKey',direct['token']],capture_output=True)
 if result.returncode:raise SystemExit('Home transfer failed. Check the authorized ADB connection and open House Control on TV.')
 confirmed=False
 for attempt in range(5):
  try:
   received=read_tv()
   if received.get('mode')=='standalone' and received.get('revision')!=previous:confirmed=True;break
  except Exception:pass
  time.sleep(1)
 if not confirmed:raise SystemExit('TV did not confirm the import. Keep House Control open and check that this Mac has the private key from this TV; a previously paired TV rejects an unknown key.')
 print('Home copied to TV. No Smart Life access or refresh tokens were transferred.')
if __name__=='__main__':main()
