#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
push_files.py —— 单文件推送脚本（无需 git，走 GitHub Contents API）

用途
----
把本地指定文件推送到 GitHub 仓库，适合手机端没有 git 的环境。
特点：**串行推送、自动取最新 sha、带重试**，避免并发导致的 sha 冲突
     （并发会报 `is at X but expected Y`）。

用法
----
    export GH_TOKEN=<你的 token>
    python3 push_files.py '远端路径1=本地路径1' '远端路径2=本地路径2'

示例
----
    export GH_TOKEN=ghp_xxxx
    python3 push_files.py \\
        'app/src/main/java/com/stronghold/shell/GamePack.kt=/sdcard/stronghold-shell/app/src/main/java/com/stronghold/shell/GamePack.kt'

环境变量
--------
    GH_TOKEN   必填，GitHub Personal Access Token（需 repo scope）

注意
----
* token 请用环境变量传入，不要写死在命令历史里。
* 本脚本只推「文本/源码」文件；大二进制（APK/zip/.so）不建议走此接口（有 100MB 上限且慢）。
"""
import os, sys, json, base64, subprocess, time

TOKEN = os.environ['GH_TOKEN']
OWNER = 'huajixiaobai'
REPO = 'stronghold-shell'
BRANCH = 'main'
API = 'https://api.github.com'


def curl(method, url, body=None):
    """用 curl 发请求（避免依赖 requests 库），返回解析后的 JSON。"""
    cmd = ['curl', '-s', '-X', method, url,
           '-H', f'Authorization: token {TOKEN}',
           '-H', 'Accept: application/vnd.github+json',
           '-H', 'User-Agent: push_files.py']
    if body is not None:
        cmd += ['-H', 'Content-Type: application/json', '-d', body]
    for _ in range(3):
        p = subprocess.run(cmd, capture_output=True)
        try:
            return json.loads(p.stdout.decode('utf-8'))
        except Exception:
            time.sleep(1)
    return {}


def get_sha(path):
    """取远端文件当前 sha（不存在则返回 None，表示新建）。"""
    r = curl('GET', f'{API}/repos/{OWNER}/{REPO}/contents/{path}?ref={BRANCH}')
    return r.get('sha') if isinstance(r, dict) else None


def put_file(local, remote):
    """推送单个文件，最多重试 4 次。"""
    with open(local, 'rb') as f:
        content = base64.b64encode(f.read()).decode()
    for attempt in range(4):
        sha = get_sha(remote)
        body = {'message': f'update {remote}', 'content': content, 'branch': BRANCH}
        if sha:
            body['sha'] = sha  # 更新已有文件必须带旧 sha
        r = curl('PUT', f'{API}/repos/{OWNER}/{REPO}/contents/{remote}', json.dumps(body))
        if isinstance(r, dict) and 'content' in r:
            print(f'  OK {remote} @ {r["content"]["sha"][:10]}')
            return True
        msg = r.get('message', '?') if isinstance(r, dict) else '?'
        print(f'  retry {attempt + 1} {remote}: {msg}')
        time.sleep(2)
    print(f'  FAIL {remote}')
    return False


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    pairs = []
    for arg in sys.argv[1:]:
        remote, local = arg.split('=', 1)  # 格式：远端=本地
        pairs.append((local, remote))
    ok = 0
    for local, remote in pairs:
        if not os.path.isfile(local):
            print(f'  MISSING {local}')
            continue
        if put_file(local, remote):
            ok += 1
    print(f'完成：{ok}/{len(pairs)}')