#!/usr/bin/env bash
set -eu
# Existing isolated checkout is used; do not create a worktree.
cd /workspace/DoCTyP_App
command -v java >/dev/null
command -v python3 >/dev/null
export ANDROID_HOME=/workspace/tooling/android-sdk
python3 - <<'PY'
import os, pathlib, urllib.request, hashlib, zipfile, xml.etree.ElementTree as E
sdk=pathlib.Path(os.environ['ANDROID_HOME'])
if not (sdk/'cmdline-tools/latest/bin/sdkmanager').exists():
    base='https://dl.google.com/android/repository/'
    name='commandlinetools-linux-11076708_latest.zip'
    root=E.fromstring(urllib.request.urlopen(base+'repository2-1.xml').read())
    archives=[a for a in root.iter('archive') if a.findtext('complete/url')==name]
    if not archives: raise RuntimeError('Official SDK checksum unavailable')
    checksum=archives[0].find('complete/checksum')
    data=urllib.request.urlopen(base+name).read()
    assert hashlib.new(checksum.attrib.get('type','sha1'),data).hexdigest()==checksum.text
    import io
    target=sdk/'cmdline-tools'; target.mkdir(parents=True,exist_ok=True)
    zipfile.ZipFile(io.BytesIO(data)).extractall(target)
    (target/'cmdline-tools').rename(target/'latest')
    for executable in (target/'latest/bin').iterdir(): executable.chmod(0o755)
PY
python3 - <<'PY'
import os,subprocess
manager=os.environ['ANDROID_HOME']+'/cmdline-tools/latest/bin/sdkmanager'
root='--sdk_root='+os.environ['ANDROID_HOME']
subprocess.run([manager,root,'--licenses'],input='y\n'*200,text=True,check=True,stdout=subprocess.DEVNULL)
subprocess.run([manager,root,'platforms;android-35','build-tools;34.0.0','build-tools;35.0.0','platform-tools','sources;android-35'],check=True)
PY
# Gradle does not consume HTTP(S)_PROXY automatically; retain proxy and TLS checks.
proxy_options=$(python3 - <<'PY'
import os,urllib.parse
for protocol in ('http','https'):
    raw=os.environ.get(protocol.upper()+'_PROXY') or os.environ.get(protocol+'_proxy')
    if raw:
        p=urllib.parse.urlparse(raw)
        if p.username or p.password: raise RuntimeError('Use platform proxy without embedded credentials')
        if p.hostname: print('-D'+protocol+'.proxyHost='+p.hostname, '-D'+protocol+'.proxyPort='+str(p.port or 80))
PY
)
export GRADLE_OPTS="${GRADLE_OPTS:-} $proxy_options"
./gradlew --no-daemon --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
