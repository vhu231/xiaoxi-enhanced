"""Read actual DEX descriptors for host ABI checks (JADX renames case collisions)."""
import argparse, json, re, struct, zipfile
from pathlib import Path

def read_dex(data):
    def u32(p): return struct.unpack_from('<I', data, p)[0]
    def u16(p): return struct.unpack_from('<H', data, p)[0]
    def leb(p):
        n=shift=0
        while True:
            b=data[p];p+=1;n|=(b&127)<<shift;shift+=7
            if b<128:return n,p
    strings=[]
    for i in range(u32(56)):
        p=u32(u32(60)+i*4);_,p=leb(p);end=data.index(0,p)
        strings.append(data[p:end].decode('utf-8','replace'))
    types=[strings[u32(u32(68)+i*4)] for i in range(u32(64))]
    protos=[]
    for i in range(u32(72)):
        p=u32(76)+12*i;off=u32(p+8)
        args=[] if off==0 else [types[u16(off+4+2*j)] for j in range(u32(off))]
        protos.append((args,types[u32(p+4)]))
    fields=[]
    for i in range(u32(80)):
        p=u32(84)+8*i;fields.append((strings[u32(p+4)],types[u16(p+2)]))
    methods=[]
    for i in range(u32(88)):
        p=u32(92)+8*i;args,ret=protos[u16(p+2)];methods.append((strings[u32(p+4)],args,ret))
    classes={}
    for i in range(u32(96)):
        p=u32(100)+32*i;name=types[u32(p)];superid=u32(p+8);off=u32(p+24)
        row={'super':types[superid] if superid!=0xffffffff else None,'fields':{},'methods':[]}
        classes[name]=row
        if not off:continue
        counts=[]
        for _ in range(4):n,off=leb(off);counts.append(n)
        for count in counts[:2]:
            index=0
            for _ in range(count):
                delta,off=leb(off);index+=delta;access,off=leb(off);name_,typ=fields[index];row['fields'][name_]=typ
        for count in counts[2:]:
            index=0
            for _ in range(count):
                delta,off=leb(off);index+=delta;access,off=leb(off);code,off=leb(off);row['methods'].append(methods[index])
    return classes

def apk_classes(apk):
    result={}
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if re.fullmatch(r'classes\d*\.dex',name):result.update(read_dex(z.read(name)))
    return result

def main():
    parser=argparse.ArgumentParser();parser.add_argument('apk');parser.add_argument('--out',default='work/host-abi-full.json');args=parser.parse_args()
    Path(args.out).parent.mkdir(parents=True,exist_ok=True)
    classes=apk_classes(args.apk);Path(args.out).write_text(json.dumps(classes,ensure_ascii=False),encoding='utf-8')
    errors=[];checks=0
    for path in Path('app/src/main/java').rglob('*.java'):
        source=path.read_text(encoding='utf-8-sig')
        for name in re.findall(r'(?:Host\.(?:cls|make)|capture)\(\s*"([\w.$]+)"',source):
            if name.startswith(('android.','java.')) or name.endswith('.'):continue
            checks+=1
            if 'L'+name.replace('.','/')+';' not in classes:errors.append(f'{path.name}: class {name}')
        for name,method in re.findall(r'hookAllMethods\(\s*Host\.cls\("([\w.$]+)"\),\s*"([\w$]+)"',source):
            checks+=1;row=classes.get('L'+name.replace('.','/')+';',{})
            if not any(m[0]==method for m in row.get('methods',[])):errors.append(f'{path.name}: method {name}.{method}')
        for match in re.finditer(r'Host.make\(\s*"([\w.$]+)"',source):
            name=match.group(1)
            if name.endswith('.'):continue
            start=match.end();depth=0;quote=None;escape=False;commas=0
            for ch in source[start:]:
                if quote:
                    if escape:escape=False
                    elif ch=='\\':escape=True
                    elif ch==quote:quote=None
                elif ch in ('"',"'"):quote=ch
                elif ch in '({[':depth+=1
                elif ch in ')}]':
                    if ch==')' and depth==0:break
                    depth-=1
                elif ch==',' and depth==0:commas+=1
            row=classes.get('L'+name.replace('.','/')+';',{})
            checks+=1
            if not any(m[0]=='<init>' and len(m[1])==commas for m in row.get('methods',[])):errors.append(f'{path.name}: constructor {name}({commas} args)')
    print(f'DEX classes: {len(classes)}; literal ABI checks: {checks}; errors: {len(errors)}')
    for error in errors:print(error)
    raise SystemExit(bool(errors))
if __name__=='__main__':main()
