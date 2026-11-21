"""Phone-side remote-file adapter; uses the unmodified desktop tag parsers and V2 writer.

The bridge is a serialized Java SSH connection. No Python or service runs on NAS.
"""
import base64
from collections import OrderedDict
from dataclasses import asdict
import io
import json
import hashlib
import os
from pathlib import Path, PurePosixPath
from types import SimpleNamespace
import uuid

import build_nas_catalog_v2 as b

CACHE = 'fakepod_android_scan_cache_v1.json'
ARTIFACTS = ('music_index_v2.bin', 'music_manifest_v2.bin',
             'fakepod_make_static_track_links.sh', CACHE, 'fakepod_catalog.meta')


class RemoteFS:
    def __init__(self, bridge, root):
        self.bridge, self.root = bridge, root.rstrip('/')
        self.blocks = OrderedDict()
        self.dirs = {}
        self.metadata = {}

    def directory(self, path):
        key = str(path)
        if key not in self.dirs:
            items = json.loads(str(self.bridge.list(key)))
            if len(items) > 100000:
                raise ValueError('单个目录文件过多')
            self.dirs[key] = items
            for item in items:
                self.metadata[str(PurePosixPath(key) / item['name'])] = item
        return self.dirs[key]

    def block(self, path, offset):
        key = (str(path), offset)
        if key not in self.blocks:
            self.blocks[key] = base64.b64decode(str(self.bridge.read(str(path), offset, 65536)))
            while len(self.blocks) > 32:
                self.blocks.popitem(last=False)
        self.blocks.move_to_end(key)
        return self.blocks[key]


class RemoteStream(io.RawIOBase):
    def __init__(self, path):
        super().__init__()
        self.path, self.pos = path, 0
        self.size = path.stat().st_size
        self.budget = 24 * 1024 * 1024

    def readable(self): return True
    def seekable(self): return True
    def tell(self): return self.pos

    def seek(self, offset, whence=0):
        self.pos = offset + (self.pos if whence == 1 else self.size if whence == 2 else 0)
        if self.pos < 0: raise ValueError('negative seek')
        return self.pos

    def read(self, count=-1):
        if count < 0: count = self.size - self.pos
        count = min(count, max(0, self.size - self.pos))
        if count > self.budget:
            raise ValueError('音频标签读取超过安全上限')
        self.budget -= count
        data = bytearray()
        while len(data) < count:
            start = self.pos // 65536 * 65536
            block = self.path.fs.block(self.path, start)
            chunk = block[self.pos - start:self.pos - start + count - len(data)]
            if not chunk: raise IOError('远程文件提前结束，可能正在被修改')
            data.extend(chunk)
            self.pos += len(chunk)
        return bytes(data)


class RemotePath:
    def __init__(self, fs, path):
        self.fs, self.p = fs, PurePosixPath(str(path))

    def __str__(self): return str(self.p)
    def __hash__(self): return hash((id(self.fs), self.p))
    def __eq__(self, other): return isinstance(other, RemotePath) and self.fs is other.fs and self.p == other.p
    @property
    def parent(self): return RemotePath(self.fs, self.p.parent)
    @property
    def suffix(self): return self.p.suffix
    @property
    def stem(self): return self.p.stem
    @property
    def name(self): return self.p.name
    def relative_to(self, root): return self.p.relative_to(PurePosixPath(str(root)))
    def stat(self, fresh=False):
        obj = self.fs.metadata.get(str(self)) if not fresh else None
        if obj is None:
            obj = json.loads(str(self.fs.bridge.stat(str(self))))
        return SimpleNamespace(st_size=obj['size'], st_mtime=obj['mtime'], st_mtime_ns=obj['mtime'] * 10**9)
    def is_file(self):
        obj = self.fs.metadata.get(str(self))
        if obj is None: obj = json.loads(str(self.fs.bridge.stat(str(self))))
        return not obj['directory']
    def iterdir(self):
        return (RemotePath(self.fs, self.p / x['name']) for x in self.fs.directory(self))
    def open(self, mode='rb'):
        if mode != 'rb': raise ValueError('read-only remote audio')
        return RemoteStream(self)


def discover(fs):
    """Never follow symlinks; bound traversal and ignore generated FakePod tree."""
    pending, files, count = [fs.root], {}, 0
    while pending:
        folder = pending.pop()
        for item in fs.directory(folder):
            name = item['name']
            if name in ('.', '..') or '/' in name or '\x00' in name:
                raise ValueError('非法 NAS 文件名')
            if folder == fs.root and name == '_FakePod': continue
            path = str(PurePosixPath(folder) / name)
            if item['directory']:
                pending.append(path)
            else:
                rel = '/' + PurePosixPath(path).relative_to(fs.root).as_posix()
                files[rel] = item
            count += 1
            if count > 200000: raise ValueError('目录条目超过20万，请缩小音乐目录')
        fs.bridge.progress('正在扫描目录：%d 个文件' % len(files))
    return files


def scan(bridge, root, scope, output, checkpoint_dir=None):
    fs = RemoteFS(bridge, root)
    root_path = RemotePath(fs, root)
    files = discover(fs)
    audio = sorted((p for p in files if Path(p).suffix.lower() in b.FORMAT_MAP), key=b.ascii_fold_key)
    if len(audio) > 100000: raise ValueError('歌曲超过10万首')
    if not audio: raise ValueError('没有发现支持的歌曲，已停止；不会发布空曲库')
    for i in range(1, len(audio)):
        if b.ascii_fold_key(audio[i-1]) == b.ascii_fold_key(audio[i]):
            raise ValueError('存在大小写冲突路径：' + audio[i])
    target = root + '/_FakePod/music-index/'
    old_meta = str(bridge.readSmall(target + 'fakepod_catalog.meta', 65536))
    text = str(bridge.readSmall(target + CACHE, 24 * 1024 * 1024))
    previous = json.loads(text) if text else {}
    if previous.get('scope') != scope or previous.get('version') != 1: previous = {}
    old = previous.get('entries', {})
    resumed = {}
    journal = None
    # Scope and companion snapshot avoid reusing stale artwork/lyrics after a restart.
    identity = dict(scope=scope, companions={p: (x['size'], x['mtime']) for p, x in files.items()
                    if Path(p).suffix.lower() in ('.lrc', '.jpg', '.jpeg', '.png')})
    key = hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()
    if checkpoint_dir:
        directory = Path(checkpoint_dir)
        directory.mkdir(parents=True, exist_ok=True)
        journal = directory / 'scan.jsonl'
        try:
            lines = journal.read_text(encoding='utf-8').splitlines()
            if json.loads(lines[0]).get('key') == key:
                for line in lines[1:]:
                    try:
                        item = json.loads(line)
                        resumed[item['path']] = item['entry']
                    except (ValueError, KeyError): pass
            else: journal.unlink()
        except (OSError, ValueError, IndexError):
            journal.unlink(missing_ok=True)
        # Compact atomically on rescan: repeated runs must not grow the journal forever.
        temporary = journal.with_suffix('.tmp')
        with temporary.open('w', encoding='utf-8') as stream:
            stream.write(json.dumps(dict(key=key)) + '\n')
            for path, entry in resumed.items():
                if path in files:
                    stream.write(json.dumps(dict(path=path, entry=entry), ensure_ascii=False) + '\n')
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(journal)
    rows, entries, art_cache, lrc_cache, ids = [], {}, {}, {}, set()
    added = modified = reused = 0
    for n, rel in enumerate(audio, 1):
        bridge.checkpoint()
        entry, remote = files[rel], RemotePath(fs, root + rel)
        published_prior = old.get(rel)
        prior = resumed.get(rel) or published_prior
        bridge.progress('读取歌曲信息 %d/%d · 标签：%s' % (n - 1, len(audio), rel))
        fmt = b.FORMAT_MAP[Path(rel).suffix.lower()]
        if prior and prior['size'] == entry['size'] and prior['mtime_ns'] == entry['mtime'] * 10**9:
            row = b.track_from_cache(rel, prior)
            if row.fmt != fmt: raise ValueError('缓存格式不一致')
            reused += 1
        else:
            row = b.build_track_meta(remote, root_path, fmt)
            if published_prior: modified += 1
            else: added += 1
        if row.track_id in ids: raise ValueError('短 ID 冲突')
        ids.add(row.track_id)
        bridge.progress('读取歌曲信息 %d/%d · 音频参数：%s' % (n - 1, len(audio), rel))
        if not (row.technical_flags & b.TECH_PARSED):
            for key, value in b.probe_technical(remote, fmt, entry['size']).items():
                setattr(row, key, value)
        bridge.progress('读取歌曲信息 %d/%d · 封面/歌词：%s' % (n - 1, len(audio), rel))
        refresh_art = not row.artwork_scanned
        if row.artwork_scanned and row.artwork_source in (0, 3):
            if remote.parent not in art_cache:
                art_cache[remote.parent] = b.find_directory_cover(remote.parent, root_path)
            current = art_cache[remote.parent]
            previous_art = (row.artwork_source, row.artwork_format, row.artwork_offset,
                            row.artwork_size, row.artwork_flags, row.artwork_width,
                            row.artwork_height, row.artwork_picture_type, row.artwork_path, row.artwork_mtime)
            refresh_art = (current != previous_art) if row.artwork_source == 3 else current is not None
        if refresh_art:
            locator = b.scan_artwork(remote, root_path, fmt, art_cache)
            for key in ('artwork_source', 'artwork_format', 'artwork_offset', 'artwork_size',
                        'artwork_flags', 'artwork_width', 'artwork_height', 'artwork_picture_type', 'artwork_mtime'):
                setattr(row, key, 0)
            row.artwork_path = ''
            if locator:
                (row.artwork_source, row.artwork_format, row.artwork_offset, row.artwork_size,
                 row.artwork_flags, row.artwork_width, row.artwork_height, row.artwork_picture_type,
                 row.artwork_path, row.artwork_mtime) = locator
        row.artwork_scanned = True
        lrc = b.matching_directory_lrc(remote, lrc_cache)
        row.lyrics_path = ''
        if lrc and 0 < lrc.stat().st_size <= 256 * 1024:
            row.lyrics_path = '/' + lrc.relative_to(root_path).as_posix()
        stat = remote.stat(fresh=True)
        if (stat.st_size, int(stat.st_mtime)) != (entry['size'], entry['mtime']):
            raise ValueError('扫描期间文件发生变化，请重新扫描：' + rel)
        rows.append(row)
        entries[rel] = dict(size=entry['size'], mtime_ns=entry['mtime'] * 10**9, track=asdict(row))
        if journal:
            with journal.open('a', encoding='utf-8') as stream:
                stream.write(json.dumps(dict(path=rel, entry=entries[rel]), ensure_ascii=False) + '\n')
                stream.flush()
                os.fsync(stream.fileno())
        bridge.progress('读取歌曲信息 %d/%d（复用 %d）'  % (n, len(audio), reused))
    bridge.checkpoint()
    index, manifest, index_crc, manifest_crc, artists, albums = b.build_catalog(rows)
    aliases = b.build_static_alias_script(rows)
    alias_crc = b.crc32(aliases)
    revision = (((index_crc << 32) | manifest_crc) ^ ((alias_crc << 1) | 0x96)) & 0xffffffffffffffff or 1
    meta = ('FAKEPOD_NAS_V1\ncatalog_version=6\nrevision=0x%016X\ntracks=%d\nartists=%d\nalbums=%d\n'
            'index_size=%d\nindex_crc32=0x%08X\nmanifest_size=%d\nmanifest_crc32=0x%08X\n'
            'track_alias_version=1\ntrack_alias_script_size=%d\ntrack_alias_script_crc32=0x%08X\n') % (
                revision, len(rows), artists, albums, len(index), index_crc, len(manifest), manifest_crc, len(aliases), alias_crc)
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    (output / ARTIFACTS[0]).write_bytes(index)
    (output / ARTIFACTS[1]).write_bytes(manifest)
    (output / ARTIFACTS[2]).write_bytes(aliases)
    (output / CACHE).write_text(json.dumps(dict(version=1, scope=scope, entries=entries), ensure_ascii=False), encoding='utf-8')
    (output / 'fakepod_catalog.meta').write_text(meta, encoding='utf-8')
    alias_rows = []
    for row in rows:
        if row.fmt in (1, 2):
            alias_rows.append((row.path, '%016X%s' % (row.track_id, Path(row.path).suffix)))
            if row.lyrics_path: alias_rows.append((row.lyrics_path, '%016X.lrc' % row.track_id))
    added = len(set(entries) - set(old))
    modified = sum(1 for p, x in entries.items() if p in old and
                   (x['size'], x['mtime_ns']) != (old[p]['size'], old[p]['mtime_ns']))
    removed = len(set(old) - set(entries))
    plan = dict(root=root, scope=scope, files=files, old_meta=old_meta, aliases=alias_rows,
                token=uuid.uuid4().hex, summary=dict(total=len(rows), added=added, modified=modified,
                                                    removed=removed, reused=reused, baseline=not bool(previous)))
    (output / 'plan.json').write_text(json.dumps(plan, ensure_ascii=False), encoding='utf-8')
    return json.dumps(plan['summary'], ensure_ascii=False)


def publish_script(plan):
    q = b.shell_single_quote
    lines = ['#!/bin/sh', 'set -eu', 'ROOT="$1"', 'STAGE="$2"',
             'INDEX="$ROOT/_FakePod/music-index"', 'TRACK="$ROOT/_FakePod/track"',
             'LOCK="$ROOT/_FakePod/.app-update-lock"',
             'mkdir "$LOCK" || { echo "Another update or unfinished recovery exists" >&2; exit 1; }',
             "same_file() { [ -f \"$1\" ] && [ -f \"$2\" ] && [ \"$(ls -di \"$1\" | awk 'NR==1 {print $1}')\" = \"$(ls -di \"$2\" | awk 'NR==1 {print $1}')\" ]; }",
             'rollback() {', '  code=$?', '  trap - EXIT HUP INT TERM', '  set +e', '  recovery_needed=0',
             '  if [ ! -f "$STAGE/COMMITTED" ]; then']
    for _, name in plan['aliases']:
        n = q(name)
        lines += ['    if [ -f "$STAGE/alias-backup"/' + n + ' ]; then',
                  '      same_file "$STAGE/alias-backup"/' + n + ' "$TRACK"/' + n + ' || mv -f "$STAGE/alias-backup"/' + n + ' "$TRACK"/' + n + ' || recovery_needed=1',
                  '    elif [ -f "$STAGE/new-names"/' + n + ' ]; then rm -f "$TRACK"/' + n + ' || recovery_needed=1; fi']
    for name in ARTIFACTS:
        n = q(name)
        lines += ['    if [ -f "$STAGE/index-backup"/' + n + ' ]; then',
                  '      cp -p "$STAGE/index-backup"/' + n + ' "$INDEX"/' + n + ' || recovery_needed=1',
                  '    elif [ -f "$STAGE/index-absent"/' + n + ' ]; then rm -f "$INDEX"/' + n + ' || recovery_needed=1; fi']
    lines += ['  fi', '  if [ "$recovery_needed" -eq 0 ]; then rmdir "$LOCK" || :; else echo "Rollback incomplete; keep lock and backups for recovery" >&2; fi', '  exit "$code"', '}',
              "trap rollback EXIT", "trap 'exit 1' HUP INT TERM",
              'mkdir -p "$TRACK" "$STAGE/new-aliases" "$STAGE/alias-backup" "$STAGE/new-names" "$STAGE/index-backup" "$STAGE/index-absent"']
    if plan['old_meta']:
        lines += ['cmp -s "$STAGE/expected.meta" "$INDEX/fakepod_catalog.meta" || { echo "Catalog changed; scan again" >&2; exit 1; }']
    else:
        lines += ['[ ! -s "$INDEX/fakepod_catalog.meta" ] || { echo "Catalog changed; scan again" >&2; exit 1; }']
    # Only link new/replaced audio; leave already-correct inodes untouched on slow NAS.
    for rel, name in plan['aliases']:
        n, source = q(name), q(rel.lstrip('/'))
        lines += ['[ ! -L "$TRACK"/' + n + ' ] || { echo "Alias is a symlink" >&2; exit 1; }',
                  'if ! same_file "$ROOT"/' + source + ' "$TRACK"/' + n + '; then',
                  '  ln "$ROOT"/' + source + ' "$STAGE/new-aliases"/' + n,
                  '  if [ -e "$TRACK"/' + n + ' ]; then',
                  '    [ -f "$TRACK"/' + n + ' ] || exit 1',
                  '    ln "$TRACK"/' + n + ' "$STAGE/alias-backup"/' + n,
                  '  else touch "$STAGE/new-names"/' + n + '; fi', 'fi']
    for name in ARTIFACTS:
        n = q(name)
        lines += ['if [ -e "$INDEX"/' + n + ' ]; then cp -p "$INDEX"/' + n + ' "$STAGE/index-backup"/' + n,
                  'else touch "$STAGE/index-absent"/' + n + '; fi']
    for _, name in plan['aliases']:
        n = q(name)
        lines += ['if [ -f "$STAGE/new-aliases"/' + n + ' ]; then mv -f "$STAGE/new-aliases"/' + n + ' "$TRACK"/' + n + '; fi']
    # Metadata is the existing firmware's commit marker and is always moved last.
    for name in ARTIFACTS:
        n = q(name)
        lines += ['mv -f "$STAGE"/' + n + ' "$INDEX"/' + n]
    lines += ['touch "$STAGE/COMMITTED"', 'echo "FAKEPOD_APP_COMMIT_OK"', '']
    return '\n'.join(lines)


def publish(bridge, root, scope, output):
    output = Path(output)
    plan = json.loads((output / 'plan.json').read_text(encoding='utf-8'))
    if (plan['root'], plan['scope']) != (root, scope): raise ValueError('扫描参数已改变，请重新扫描')
    bridge.progress('正在再次核对 NAS 文件，准备上传…')
    files = discover(RemoteFS(bridge, root))
    # Compare supported audio AND lyrics/covers: metadata-only edits must not publish stale refs.
    def snapshot(items):
        return {p: (x['size'], x['mtime']) for p, x in items.items()
                if Path(p).suffix.lower() in (*b.FORMAT_MAP, '.lrc', '.jpg', '.jpeg', '.png')}
    if snapshot(files) != snapshot(plan['files']): raise ValueError('扫描后音乐/歌词/封面发生变化，请重新扫描')
    target = root + '/_FakePod/music-index/'
    if str(bridge.readSmall(target + 'fakepod_catalog.meta', 65536)) != plan['old_meta']:
        raise ValueError('曲库已被其他工具更新，请重新扫描')
    stage = root + '/_FakePod/.app-stage-' + plan['token']
    bridge.mkdir(stage)
    script = output / 'publish.sh'
    script.write_text(publish_script(plan), encoding='utf-8', newline='\n')
    (output / 'expected.meta').write_text(plan['old_meta'], encoding='utf-8')
    for name in (*ARTIFACTS, 'publish.sh', 'expected.meta'):
        bridge.progress('上传并校验：' + name)
        bridge.upload(str(output / name), stage + '/' + name)
    # Recheck the visible revision after upload and before any live changes.
    if str(bridge.readSmall(target + 'fakepod_catalog.meta', 65536)) != plan['old_meta']:
        raise ValueError('上传期间曲库已被更新，请重新扫描')
    result = str(bridge.publish(stage + '/publish.sh', stage))
    if 'FAKEPOD_APP_COMMIT_OK' not in result: raise ValueError('未收到发布完成标记，请检查 NAS 备份目录')
    return '更新完成。请在 FakePod 点击「NAS曲库更新」加载新曲库。无需重启设备。'
