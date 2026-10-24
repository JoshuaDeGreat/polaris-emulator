#!/usr/bin/env python3
import json, os, time, urllib.request, subprocess
from datetime import datetime, timezone
OWNER=os.environ.get('HEAVEN_OPS_OWNER','JoshuaDeGreat'); REPO=os.environ.get('HEAVEN_OPS_REPO','polaris-emulator'); ALLOWED_AUTHOR=os.environ.get('HEAVEN_OPS_ALLOWED_AUTHOR','JoshuaDeGreat').lower(); TOKEN=os.environ['HEAVEN_OPS_GITHUB_TOKEN']; POLL_SECONDS=max(30,int(os.environ.get('HEAVEN_OPS_POLL_SECONDS','60'))); LABEL='heaven-ops-approved'; QUEUE_LABEL='heaven-ops-request'; HELPER_PATH='/usr/local/sbin/heaven-ops'; OPS={'status','containers','player_api','recovery_logs','recovery_classes'}; BASE='https://api.github.com/repos/'+OWNER+'/'+REPO
def api(path,method='GET',payload=None):
 data=None if payload is None else json.dumps(payload).encode(); req=urllib.request.Request(BASE+path,data=data,method=method,headers={'Accept':'application/vnd.github+json','Authorization':'Bearer '+TOKEN,'X-GitHub-Api-Version':'2022-11-28','User-Agent':'HeavenOps-Agent','Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=20) as r:
  raw=r.read(); return json.loads(raw) if raw else {}
def comment(number,text): api('/issues/'+str(number)+'/comments','POST',{'body':text[:60000]})
def process(issue):
 number=issue['number']; author=(issue.get('user') or {}).get('login','').lower(); labels={x['name'] for x in issue.get('labels',[])}
 if author!=ALLOWED_AUTHOR or QUEUE_LABEL not in labels or LABEL not in labels: return
 marker='<!-- heaven-ops-result -->'; comments=api('/issues/'+str(number)+'/comments?per_page=100')
 if any(marker in (c.get('body') or '') for c in comments): return
 try: spec=json.loads(issue.get('body') or '{}')
 except json.JSONDecodeError: comment(number,marker+'\nRejected: issue body must be JSON, e.g. {"operation":"player_api"}.'); return
 op=spec.get('operation') if isinstance(spec,dict) else None
 if op not in OPS or set(spec)!={'operation'}: comment(number,marker+'\nRejected: operation not allowed. Allowed: '+', '.join(sorted(OPS))); return
 try:
  p=subprocess.run(['/usr/bin/sudo','-n',HELPER_PATH,op],capture_output=True,text=True,timeout=30,check=False); output=(p.stdout.strip() or p.stderr.strip() or '(no output)')[-10000:]; output=output.replace('```','` ` `'); comment(number,marker+'\nHeaven Ops result for '+op+' at '+datetime.now(timezone.utc).isoformat()+' (exit '+str(p.returncode)+'):\n\n```json\n'+output+'\n```')
 except Exception as e: comment(number,marker+'\nAgent execution failed: '+type(e).__name__+': '+str(e)[:300])
def main():
 print('Heaven Ops agent started; read-only operations only.',flush=True)
 while True:
  try:
   issues=api('/issues?state=open&labels='+QUEUE_LABEL+'&per_page=50')
   for issue in issues: process(issue)
  except Exception as e: print('poll failed: '+type(e).__name__+': '+str(e)[:250],flush=True)
  time.sleep(POLL_SECONDS)
if __name__=='__main__': main()