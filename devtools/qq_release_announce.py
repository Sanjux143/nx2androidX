#!/usr/bin/env python3
"""Announce a release in a QQ group: the QQ bot (bot.q.qq.com) posts the version, its Chinese changelog, the
release's page and the netdisk link to a group.

The build workflow (.github/workflows/android-apk.yml) calls this after the APK is attached to the release. The
announcement is an extra: it must never fail the build, so the workflow's step is continue-on-error and every
call here has a short timeout.

The bot's tickets and the group's id are secrets (QQBOT_APP_ID, QQBOT_APP_SECRET, QQBOT_GROUP_OPENID). The
script never prints them. The group's id is found once, by hand:

  python3 devtools/qq_release_announce.py listen        # @ the bot in the group, the id is printed

Usage:
  python3 devtools/qq_release_announce.py announce --tag v0.3.0-alpha14
  python3 devtools/qq_release_announce.py announce --tag v0.3.0-alpha14 --dry-run
"""
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request

# The QQ bot's API (bot.q.qq.com/wiki/develop/api-v2): the sandbox is the same paths on another host.
API = 'https://api.sgroup.qq.com'
SANDBOX_API = 'https://sandbox.api.sgroup.qq.com'
TOKEN_URL = 'https://bots.qq.com/app/getAppAccessToken'
GITHUB_API = 'https://api.github.com'

# The netdisk folder the releases are shared from: a folder's share link stays the same, so the link in the
# announcement is a constant and every new version is simply added to the folder.
QUARK_LINK = 'https://pan.quark.cn/s/683a51e3b989'
QUARK_CODE = '亝已森五枫并词卡哎目五忛'
QUARK_NAME = 'nx2AndroidX'

# The announcement's shape: a title, a few lines of the changelog and the two links.
MAX_BULLETS = 4
MAX_BULLET_CHARS = 42
MAX_CONTENT_CHARS = 600
TIMEOUT = 10   # seconds; a hung call must not hold the workflow

# The marker that says this release was announced already: the release's body carries it, so a rebuilt tag
# does not post the same announcement twice.
MARKER = '<!-- qq-announced -->'


def say(msg):
    print(msg, flush=True)


def http(method, url, headers=None, body=None, timeout=TIMEOUT):
    """(status, data) of a JSON call; data is the parsed body (a dict) or the text when it is not JSON"""
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    req.add_header('User-Agent', 'nx2android-release-bot')   # the GitHub API refuses a request without one
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode('utf-8', 'replace')
            status = r.status
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8', 'replace')
        status = e.code
    except Exception as e:      # a network failure is a failure like any other: the caller reports it
        return 0, {'error': str(e)}
    try:
        return status, json.loads(raw)
    except ValueError:
        return status, {'text': raw}


def access_token(app_id, app_secret, base):
    """the bot's access token (the AppSecret one, about two hours)"""
    status, data = http('POST', TOKEN_URL, body={'appId': app_id, 'clientSecret': app_secret})
    if status != 200 or not isinstance(data, dict) or 'access_token' not in data:
        return None, f'the access token was refused ({status}): {data.get("message", data)}'
    return data['access_token'], None


def release_body(repo, tag, token):
    """the release's notes (the bilingual text the release page shows), the release's id, and whether the
    release is there at all"""
    headers = {'Accept': 'application/vnd.github+json'}
    if token:
        headers['Authorization'] = f'Bearer {token}'
    status, data = http('GET', f'{GITHUB_API}/repos/{repo}/releases/tags/{tag}', headers=headers)
    if status != 200 or not isinstance(data, dict):
        return None, None, False
    return data.get('body') or '', data.get('id'), True


def mark_announced(repo, release_id, body, token):
    """put the marker in the release's notes, so a rebuilt tag does not announce the same version twice.
    The release is addressed by its id: the API takes no tag there."""
    if not token or not release_id or not body or MARKER in body:
        return False
    headers = {'Accept': 'application/vnd.github+json', 'Authorization': f'Bearer {token}'}
    payload = {'body': body.rstrip() + '\n\n' + MARKER + '\n'}
    status, _ = http('PATCH', f'{GITHUB_API}/repos/{repo}/releases/{release_id}', headers=headers, body=payload)
    return status == 200


def clean(text):
    """the changelog's own markdown out of a line (the bold markers, the backticks)"""
    text = re.sub(r'\*\*(.+?)\*\*', r'\1', text)
    text = text.replace('`', '')
    return text.strip()


def shorten(text, limit):
    """the line cut to its limit, with an ellipsis. The cut prefers a punctuation mark near the end, so the
    line reads as a sentence rather than as a word cut in half"""
    text = clean(text)
    if len(text) <= limit:
        return text
    head = text[:limit - 1]
    for mark in ('、', '，', '；', '。', ',', ';', ' ', '—'):
        cut = head.rfind(mark)
        if cut >= int(limit * 0.6):
            return head[:cut].rstrip() + '…'
    return head.rstrip() + '…'


def chinese_bullets(body):
    """the release's own Chinese changelog (the notes' 中文 part): one line per section, in the order a
    player reads them"""
    if not body or '更新日志' not in body:
        return []
    part = body.split('更新日志', 1)[1]
    part = re.split(r'\n## ', part, maxsplit=1)[0]      # the Chinese part ends at the next top-level heading
    order = (('新游戏', '新游戏'), ('更新前必读', '更新前'), ('新增', '新'), ('修复', '修复'), ('引擎', '引擎'))
    out = []
    for heading, label in order:
        m = re.search(r'### ' + heading + r'[^\n]*\n(.*?)(?=\n### |\n## |\Z)', part, re.S)
        if not m:
            continue
        bullet = next((ln for ln in m.group(1).splitlines() if ln.strip().startswith('-')), None)
        if not bullet:
            continue
        out.append((label, shorten(bullet.lstrip('- ').strip(), MAX_BULLET_CHARS)))
    return out


def english_bullets(changelog, tag):
    """the fallback: the repository's own changelog section (English) when the release's notes carry no
    Chinese part"""
    if not changelog:
        return []
    version = tag[1:] if tag.startswith('v') else tag
    m = None
    for name in (version, 'v' + version):     # the changelog's headings carry the v: "## v0.3.0-alpha14"
        m = re.search(r'^## ' + re.escape(name) + r'[^\n]*\n(.*?)(?=^## |\Z)', changelog, re.S | re.M)
        if m:
            break
    if not m:
        return []
    order = (('New games', 'new game'), ('New', 'new'), ('Fixed', 'fixed'))
    out = []
    for heading, label in order:
        mm = re.search(r'^### ' + heading + r'[^\n]*\n(.*?)(?=^### |\Z)', m.group(1), re.S | re.M)
        if not mm:
            continue
        bullet = next((ln for ln in mm.group(1).splitlines() if ln.strip().startswith('-')), None)
        if not bullet:
            continue
        out.append((label, shorten(bullet.lstrip('- ').strip(), MAX_BULLET_CHARS)))
    return out


def build_content(tag, bullets, links=True):
    """the announcement: the version, the changelog's lines and the two links"""
    lines = [f'📱 nx2androidX {tag}', '']
    for label, text in bullets[:MAX_BULLETS]:
        lines.append(f'• {label}: {text}')
    if bullets:
        lines.append('')
    if links:
        lines.append(f'GitHub: https://github.com/zFitness/nx2androidX/releases/tag/{tag}')
        lines.append(f'夸克网盘「{QUARK_NAME}」{QUARK_LINK} 提取码 {QUARK_CODE}')
        # 游戏档案包：App 不带档案（几百 MB 的着色器表），移植还没移植的游戏时在「环境检查」里导入
        lines.append('游戏档案包（可选，移植未移植的游戏时在环境检查里导入）：见 release 附件 nx2a-profiles-*.zip')
    else:
        lines.append('下载方式见群公告。')
    content = '\n'.join(lines)
    return content[:MAX_CONTENT_CHARS]


def send_group_message(openid, token, content, base=API):
    """(sent, detail): the group takes the message or refuses it (the code says why)"""
    status, data = http('POST', f'{base}/v2/groups/{openid}/messages',
                         headers={'Authorization': f'QQBot {token}'},
                         body={'msg_type': 0, 'content': content})
    if status == 200:
        return True, 'the group took it'
    if isinstance(data, dict):
        message = data.get('message') or data.get('msg') or ''
        code = data.get('code') or ''
    else:
        message, code = str(data), ''
    return False, (f'{status}: {code} {message}'.strip() if code else f'{status}: {message}')


def group_openids(raw):
    """the groups the announcement goes to: the secret holds them separated by commas (or lines, or spaces)"""
    return [g for g in re.split(r'[,\s;]+', raw or '') if g]


def announce(args):
    tag = args.tag
    repo = args.repo
    base = SANDBOX_API if args.sandbox else API

    gh_token = os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN') or ''
    body, release_id, found = release_body(repo, tag, gh_token)
    if not found:
        say(f'{tag}: the release is not there: nothing to announce')
        return 0
    if body and MARKER in body:
        say(f'{tag}: the release carries the marker already: nothing to announce')
        return 0

    changelog = ''
    path = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'CHANGELOG.md')
    if os.path.isfile(path):
        with open(path, encoding='utf-8') as f:
            changelog = f.read()
    bullets = chinese_bullets(body) or english_bullets(changelog, tag)
    if not bullets:
        bullets = [('release', 'a new version is out')]

    content = build_content(tag, bullets)
    if args.dry_run:
        say('--- the announcement ---')
        say(content)
        say('--- (dry run: nothing was sent) ---')
        return 0

    app_id = os.environ.get('QQBOT_APP_ID', '')
    app_secret = os.environ.get('QQBOT_APP_SECRET', '')
    openids = group_openids(os.environ.get('QQBOT_GROUP_OPENID', ''))
    missing = [n for n, v in (('QQBOT_APP_ID', app_id), ('QQBOT_APP_SECRET', app_secret),
                              ('QQBOT_GROUP_OPENID', openids)) if not v]
    if missing:
        say(f'{tag}: the secrets are missing ({", ".join(missing)}): nothing was announced')
        return 1

    token, err = access_token(app_id, app_secret, base)
    if not token:
        say(f'{tag}: {err}: nothing was announced')
        return 1

    told = []
    for openid in openids:
        sent, detail = send_group_message(openid, token, content, base)
        if not sent and ('40054010' in detail or 'URL' in detail):
            # the bot may not send a URL: the announcement goes without it, so the group still hears of it
            say(f'{tag}: {openid}: the group refused the links ({detail}): the announcement goes without them')
            sent, detail = send_group_message(openid, token, build_content(tag, bullets, links=False), base)
        if sent:
            told.append(openid)
            say(f'{tag}: {openid}: the group was told ({detail})')
        else:
            # a group that does not take the message does not hold the other groups back
            say(f'{tag}: {openid}: the announcement did not go through ({detail})')
    if not told:
        say(f'{tag}: no group took the announcement')
        return 1
    say(f'{tag}: {len(told)} of {len(openids)} group(s) were told')
    if body and not mark_announced(repo, release_id, body, gh_token):
        say(f'{tag}: the marker could not be written into the release (the build is fine)')
    return 0


def listen(args):
    """the group's id: the bot is @-ed in the group (or is added to it) and the event carries it"""
    try:
        import asyncio
        import botpy
    except ImportError:
        say('the QQ bot SDK is missing: pip install qq-botpy (the announcement itself needs no SDK)')
        return 1

    app_id = os.environ.get('QQBOT_APP_ID', '')
    app_secret = os.environ.get('QQBOT_APP_SECRET', '')
    if not app_id or not app_secret:
        say('QQBOT_APP_ID and QQBOT_APP_SECRET must be in the environment')
        return 1

    # Python 3.10 and later have no implicit event loop, and the SDK asks for one
    asyncio.set_event_loop(asyncio.new_event_loop())

    class Listen(botpy.Client):
        async def on_group_at_message_create(self, message):
            say(f'group_openid: {message.group_openid}')
            say('put it in the repository secret QQBOT_GROUP_OPENID')

        async def on_group_add_robot(self, event):
            say(f'group_openid (the bot was added): {event.group_openid}')
            say('put it in the repository secret QQBOT_GROUP_OPENID')

    say('add the bot to the group, or @ it there: the id is printed here')
    Listen(intents=botpy.Intents(public_messages=True)).run(app_id, app_secret)
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd')

    a = sub.add_parser('announce', help='post the announcement to the group')
    a.add_argument('--tag', default=os.environ.get('GITHUB_REF_NAME', ''), help="the release's tag")
    a.add_argument('--repo', default=os.environ.get('GITHUB_REPOSITORY') or 'zFitness/nx2androidX')
    a.add_argument('--dry-run', action='store_true', help='print the announcement, send nothing')
    a.add_argument('--sandbox', action='store_true', help='the sandbox host (a bot that is not in production)')
    a.set_defaults(func=announce)

    l = sub.add_parser('listen', help="print the group's id (once, by hand)")
    l.set_defaults(func=listen)

    args = ap.parse_args()
    if not getattr(args, 'func', None):
        ap.print_help()
        return 2
    if getattr(args, 'cmd', '') == 'announce' and not args.tag:
        say('the tag is missing: --tag v0.3.0-alpha14')
        return 2
    return args.func(args)


if __name__ == '__main__':
    sys.exit(main())
