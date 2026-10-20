#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""按实际核心的非 dev 依赖图收集许可文本，生成可审查清单。"""
import json
from pathlib import Path
import shutil
import subprocess
import sys

root=Path(__file__).resolve().parents[1]
platform=sys.argv[1]
out=Path(sys.argv[2])
metadata=json.loads(subprocess.check_output(["cargo","metadata","--locked","--format-version","1","--filter-platform",platform],cwd=root,text=True))
packages={p["id"]:p for p in metadata["packages"]}
nodes={n["id"]:n for n in metadata["resolve"]["nodes"]}
queue=[p["id"] for p in metadata["packages"] if p["name"]=="zapime-core"]
seen=set()
while queue:
    item=queue.pop()
    if item in seen:continue
    seen.add(item)
    for dep in nodes[item]["deps"]:
        if any(kind["kind"]!="dev" for kind in dep["dep_kinds"]):queue.append(dep["pkg"])
inventory=[]
for item in sorted(seen):
    package=packages[item]
    source=Path(package["manifest_path"]).parent
    texts=sorted({p for pattern in ["LICENSE*","LICENCE*","COPYING*","NOTICE*","COPYRIGHT*","Copyright*","AUTHORS*"] for p in source.glob(pattern) if p.is_file()})
    destination=out/(package["name"]+"-"+package["version"])
    destination.mkdir(parents=True,exist_ok=True)
    for path in texts:shutil.copyfile(path,destination/path.name)
    (destination/"SOURCE.txt").write_text(package["name"]+" "+package["version"]+"\n"+(package.get("repository") or "ZapIME repository and pinned upstream source")+"\n"+"Unmodified package source is available from the recorded Cargo registry/git source.\n")
    manual={
        ("ewts","0.1.4"): "ewts-MIT.txt",
        ("exmex","0.21.0"): "exmex-0.21.0-MIT.txt",
        ("jni-sys-macros","0.4.1"): "jni-sys-macros-0.4.1-MIT.txt",
        ("rink-core","0.9.0"): "MPL-2.0.txt",
    }.get((package["name"],package["version"]))
    if not texts and manual:
        shutil.copyfile(root/"third_party/licenses"/manual,destination/manual)
    inventory.append({"name":package["name"],"version":package["version"],"license":package.get("license") or ("MIT" if package["name"]=="vi" and package["version"]=="0.8.0" else "NOASSERTION"),"source":package.get("source") or "repository-source","license_files":[p.name for p in texts] + ([manual] if manual and not texts else [])})
(out/"inventory.json").write_text(json.dumps(inventory,indent=2)+"\n")
missing=[p["name"] for p in inventory if not p["license_files"]]
print("Collected",len(inventory),"dependency entries; no bundled license file:",missing)
# 仓库自身及上游路径 crate 的根许可另外随 APK 打包；其他缺项必须人工补齐。
unknown=[name for name in missing if name not in {"zapime-core","zapime-data","msime-engine","msime-path-trust"}]
if any(p["license"] == "NOASSERTION" for p in inventory):raise SystemExit("Unreviewed dependency license")
if unknown:raise SystemExit("Review missing dependency license files: "+", ".join(unknown))
