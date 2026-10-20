#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""在已启动的专用模拟器里点击真实键盘；仅使用合成测试文本。"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

adb, serial = sys.argv[1:3]
root = Path(__file__).resolve().parents[1] / "target/device-smoke"
root.mkdir(parents=True, exist_ok=True)
def shell(*args):
    return subprocess.check_output([adb, "-s", serial, "shell", *args], text=True, timeout=60)
def hierarchy():
    shell("uiautomator", "dump", "/sdcard/zapime-smoke.xml")
    xml = shell("cat", "/sdcard/zapime-smoke.xml")
    (root / "last-ui.xml").write_text(xml)
    return ET.fromstring(xml)
def tap(node):
    a,b,c,d = map(int, re.findall(r"\d+",node.attrib["bounds"]))
    shell("input", "tap",str((a+c)//2),str((b+d)//2))
def find_text(text, tree):
    return next((n for n in tree.iter("node") if n.attrib.get("text")==text or n.attrib.get("content-desc")==text),None)
time.sleep(3)
tree=hierarchy()
editor=next(n for n in tree.iter("node") if n.attrib.get("class")=="android.widget.EditText")
tap(editor)
for attempt in range(25):
    time.sleep(2)
    tree=hierarchy()
    if find_text("⚡ 闪电 · 离线",tree) is not None:break
else:
    raise AssertionError("Native keyboard did not become ready")
for c in "nihao":
    node=find_text(c,hierarchy())
    assert node is not None,"Keyboard key missing"
    tap(node)
for attempt in range(8):
    tree=hierarchy()
    word=find_text("你好",tree)
    if word is not None:break
    time.sleep(1)
assert word is not None,"Pinyin candidate missing"
tap(word)
time.sleep(1)
tree=hierarchy()
assert any(n.attrib.get("class")=="android.widget.EditText" and n.attrib.get("text")=="你好" for n in tree.iter("node")),"Candidate did not commit into real editor"
(root / "pinyin-commit.xml").write_text(ET.tostring(tree,encoding="unicode"))
subprocess.run([adb,"-s",serial,"shell","screencap","-p","/sdcard/zapime-smoke.png"],check=True)
subprocess.run([adb,"-s",serial,"pull","/sdcard/zapime-smoke.png",str(root/"pinyin-commit.png")],check=True)
print("PASS Android API 28: native keyboard activation, offline nihao candidate, real InputConnection commit",flush=True)
