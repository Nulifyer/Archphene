#!/usr/bin/env python3
import argparse, json, pathlib, shutil, subprocess, sys, tarfile, urllib.parse, urllib.request, uuid

def fields(path):
    result={}; current=None
    for line in path.read_text(errors='replace').splitlines():
        if line.startswith('%') and line.endswith('%'):
            current=line.strip('%'); result.setdefault(current,[])
        elif current and line: result[current].append(line)
    return result

def package(path,repo):
    f=fields(path)
    if not f.get('NAME'): return None
    one=lambda name: f.get(name,[''])[0]
    return {'name':one('NAME'),'version':one('VERSION'),'repo':repo,'filename':one('FILENAME'),'depends':f.get('DEPENDS',[]),'provides':f.get('PROVIDES',[])}

def dep_name(value):
    for marker in '<>=': value=value.split(marker,1)[0]
    return value.strip()

def download(url,out,refresh,container):
    if out.exists() and not refresh:return
    print('download',url); out.parent.mkdir(parents=True,exist_ok=True)
    if container:
        remote='/tmp/archphene-download-'+uuid.uuid4().hex
        subprocess.run(['podman','exec',container,'curl','--fail','--show-error','--silent','--location','--retry','3','--retry-delay','2','--output',remote,url],check=True)
        subprocess.run(['podman','cp',f'{container}:{remote}',str(out)],check=True); subprocess.run(['podman','exec',container,'rm','-f',remote],check=True)
    else:
        subprocess.run(['curl','-L','--fail','--retry','3','--retry-delay','2','-o',str(out),url],check=True)

def extract(archive,dest,strict=True):
    result=subprocess.run(['tar','-xf',str(archive),'-C',str(dest)])
    if result.returncode and strict: raise SystemExit(f'tar failed for {archive}')
    if result.returncode: print(f'warning: tar returned {result.returncode} for {archive}; continuing',file=sys.stderr)

ap=argparse.ArgumentParser(); ap.add_argument('--root',required=True); ap.add_argument('--package',default='kcalc'); ap.add_argument('--arch',default='x86_64'); ap.add_argument('--mirror',default='https://geo.mirror.pkgbuild.com'); ap.add_argument('--repository-path',default='{repo}/os/{arch}'); ap.add_argument('--work-directory'); ap.add_argument('--classification'); ap.add_argument('--download-container',default=''); ap.add_argument('--download-signatures',action='store_true'); ap.add_argument('--refresh',action='store_true'); ap.add_argument('--curated',action='store_true'); a=ap.parse_args()
root=pathlib.Path(a.root); classification=None
if a.classification: classification=json.loads((root/a.classification).read_text()); a.package=classification['linuxPackage']
work=root/a.work_directory if a.work_directory else root/'tooling/downloads'/f'arch-{"curated-" if a.curated else "runtime-"}{a.package}-{a.arch}'
dbdir=work/'db'; pkgdir=work/'packages'; runtime=work/'runtime-root'; [p.mkdir(parents=True,exist_ok=True) for p in (work,dbdir,pkgdir,runtime)]
repos=('core','extra')
def repo_url(repo): return a.mirror.rstrip('/')+'/'+a.repository_path.format(repo=repo,arch=a.arch).strip('/')
for repo in repos:
    db=dbdir/f'{repo}.db'; target=dbdir/repo; download(repo_url(repo)+f'/{repo}.db',db,a.refresh,a.download_container)
    if target.exists() and a.refresh: shutil.rmtree(target)
    if not target.exists(): target.mkdir(); extract(db,target)
all_packages={}; providers={}
for repo in repos:
    for desc in (dbdir/repo).rglob('desc'):
        p=package(desc,repo)
        if not p:continue
        all_packages.setdefault(p['name'],p)
        for provided in p['provides']: providers.setdefault(dep_name(provided),[]).append(p)
def resolve(name): return all_packages.get(dep_name(name)) or (providers.get(dep_name(name)) or [None])[0]
missing=[]; selected=[]
if a.curated:
    wanted=[classification['linuxPackage'],*classification.get('shipRequired',[])]
    if any(str(x).startswith('breeze-icons') for x in classification.get('shipAssets',[])): wanted.append('breeze-icons')
    for name in sorted(set(wanted)):
        p=resolve(name); selected.append(p) if p else missing.append(name)
else:
    queue=[a.package]; seen=set()
    while queue:
        name=queue.pop(0); p=resolve(name)
        if not p:
            if name not in missing:missing.append(name)
            continue
        if p['name'] in seen:continue
        seen.add(p['name']); selected.append(p); queue.extend(p['depends'])
selected.sort(key=lambda p:(p['repo'],p['name']))
manifest=work/('curated-manifest.tsv' if a.curated else 'runtime-manifest.tsv')
with manifest.open('w') as f:
    for p in selected:
        row=[p['name'],p['version'],p['repo'],p['filename']]
        if a.curated:row.append(', '.join(p['depends']))
        f.write('\t'.join(row)+'\n')
if missing:(work/('missing-curated.txt' if a.curated else 'missing-deps.txt')).write_text('\n'.join(sorted(set(missing)))+'\n')
for p in selected:
    if not p['filename']:continue
    local=p['filename'].replace(':','_'); url=repo_url(p['repo'])+'/'+urllib.parse.quote(p['filename'])
    download(url,pkgdir/local,a.refresh,a.download_container)
    if a.curated and a.download_signatures: download(url+'.sig',pkgdir/(local+'.sig'),a.refresh,a.download_container)
if a.curated and a.refresh and runtime.exists(): shutil.rmtree(runtime); runtime.mkdir()
for archive in pkgdir.glob('*.pkg.tar.*'):
    if archive.name.endswith('.sig'):continue
    print('extract',archive.name); extract(archive,runtime,False)
summary={'Package':a.package,'Architecture':a.arch,('SelectedPackageCount' if a.curated else 'PackageCount'):len(selected),('MissingPackageCount' if a.curated else 'MissingDependencyCount'):len(missing),'WorkDir':str(work),'Manifest':str(manifest),'RuntimeRoot':str(runtime)}
(work/'summary.json').write_text(json.dumps(summary,indent=2)+'\n'); print(json.dumps(summary,indent=2))
if missing:print('warning: missing packages: '+', '.join(missing),file=sys.stderr)
