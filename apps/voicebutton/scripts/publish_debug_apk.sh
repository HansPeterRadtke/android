#!/usr/bin/env bash
set -euo pipefail
REPO=$(cd "$(dirname "$0")/../../.." && pwd)
python3 - "$REPO" <<'PYTHON'
from pathlib import Path
import hashlib,json,os,re,shutil,sys,tempfile
repo=Path(sys.argv[1]);output=repo/'apps/voicebutton/app/build/outputs/apk/debug'
metadata=json.loads((output/'output-metadata.json').read_text())
element=metadata['elements'][0];version=element['versionName']
if not re.fullmatch(r'[0-9]+(?:[.][0-9]+)+',version):raise SystemExit('Unsafe APK version')
apk=output/element['outputFile']
upload=Path('/data/var/web_portal/uploads');upload.mkdir(parents=True,exist_ok=True)
def digest(path):
 value=hashlib.sha256()
 with path.open('rb') as source:
  for chunk in iter(lambda:source.read(1048576),b''):value.update(chunk)
 return value.hexdigest()
expected=digest(apk)
for name in ['voicebutton-'+version+'.apk','voicebutton-latest.apk']:
 destination=upload/name
 fd,temporary=tempfile.mkstemp(prefix='.'+name+'.',suffix='.tmp',dir=upload)
 try:
  with os.fdopen(fd,'wb') as target,apk.open('rb') as source:
   shutil.copyfileobj(source,target,1048576);target.flush();os.fsync(target.fileno())
  temporary=Path(temporary)
  if digest(temporary)!=expected:raise IOError('Published APK hash mismatch')
  os.chmod(temporary,0o644);os.replace(temporary,destination)
 finally:
  if Path(temporary).exists():Path(temporary).unlink()
 fd=os.open(upload,os.O_RDONLY);os.fsync(fd);os.close(fd)
 print(name,apk.stat().st_size,expected)
PYTHON
