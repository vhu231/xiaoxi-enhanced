import sys
import zipfile
from audit_host import read_dex

REQUIRED = [
    'META-INF/xposed/java_init.list',
    'META-INF/xposed/scope.list',
    'META-INF/xposed/module.prop',
]

def main():
    apk = sys.argv[1]
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        for name in REQUIRED:
            if name not in names:
                raise AssertionError('Missing API 102 metadata: ' + name)
        if 'assets/xposed_init' in names:
            raise AssertionError('Legacy xposed_init is still packaged')
        entry = archive.read(REQUIRED[0]).decode().strip()
        if entry != 'io.github.yuan.xiaoxibyok.MainHook':
            raise AssertionError('Unexpected Java entry: ' + entry)
        scope = archive.read(REQUIRED[1]).decode().split()
        if scope != ['com.meizu.voiceassistant']:
            raise AssertionError('Unexpected scope: ' + repr(scope))
        properties = dict(
            line.split('=', 1)
            for line in archive.read(REQUIRED[2]).decode().splitlines()
            if line.strip() and not line.startswith('#'))
        if properties.get('minApiVersion') != '102' or properties.get('targetApiVersion') != '102':
            raise AssertionError('API 102 min/target versions are not configured')
        if properties.get('staticScope') != 'true' or 'autoHotReload' in properties:
            raise AssertionError('Unexpected scope/hot reload configuration')
        classes = {}
        for name in names:
            if name.startswith('classes') and name.endswith('.dex'):
                classes.update(read_dex(archive.read(name)))
    forbidden = ('Lde/robv/android/xposed/', 'Lio/github/libxposed/api/', 'Landroidx/annotation/', 'Lio/github/libxposed/annotation/')
    for name in classes:
        if name.startswith(forbidden):
            raise AssertionError('Framework class packaged in module DEX: ' + name)
    if 'Lio/github/yuan/xiaoxibyok/MainHook;' not in classes:
        raise AssertionError('MainHook is not in the module DEX')
    print(f'API 102 package: metadata OK; module DEX classes: {len(classes)}; forbidden definitions: 0')

if __name__ == '__main__':
    main()
