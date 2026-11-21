"""Compatibility and publication fault tests: python -m unittest discover -s tests -v."""
import base64
from dataclasses import asdict
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'app/src/main/python'))
import mobile_nas_catalog as mobile
import build_nas_catalog_v2 as desktop


class Bridge:
    def __init__(self):
        self.read_bytes = 0
        self.messages = []
        self.fail_publish = False
    def checkpoint(self): pass
    def progress(self, message): self.messages.append(message)
    def list(self, folder):
        return json.dumps([dict(name=p.name, directory=p.is_dir(), size=p.stat().st_size,
                                mtime=int(p.stat().st_mtime)) for p in Path(folder).iterdir()])
    def stat(self, path):
        p = Path(path); st = p.stat()
        return json.dumps(dict(size=st.st_size, mtime=int(st.st_mtime), directory=p.is_dir()))
    def read(self, path, offset, count):
        with open(path, 'rb') as stream:
            stream.seek(offset); data = stream.read(count)
        self.read_bytes += len(data)
        return base64.b64encode(data).decode()
    def readSmall(self, path, limit):
        p = Path(path)
        return p.read_text(encoding='utf-8') if p.exists() else ''
    def mkdir(self, path): Path(path).mkdir(exist_ok=True)
    def upload(self, source, dest): shutil.copyfile(source, dest)
    def publish(self, script, stage):
        env = dict(os.environ)
        if self.fail_publish:
            tools = Path(stage) / 'fault-bin'; tools.mkdir()
            fault = tools / 'mv'
            # Fail once after aliases and the first index have already moved.
            fault.write_text('#!/bin/sh\ncase "$2" in */music_manifest_v2.bin) if [ ! -f "'+stage+'/failed-once" ]; then touch "'+stage+'/failed-once"; exit 72; fi;; esac\nexec /usr/bin/mv "$@"\n')
            fault.chmod(0o755)
            env['PATH'] = str(tools) + ':' + env['PATH']
        proc = subprocess.run(['sh', script, str(Path(stage).parent.parent), stage],
                              capture_output=True, text=True, env=env)
        if proc.returncode: raise RuntimeError(proc.stderr or 'injected publication failure')
        return proc.stdout


def id3_frame(name, text):
    data = b'\x03' + text.encode()
    return name.encode() + len(data).to_bytes(4, 'big') + b'\0\0' + data


def make_mp3(path, title='歌曲标题'):
    data = b''.join(id3_frame(k,v) for k,v in [('TIT2',title),('TPE1','歌手'),('TALB','专辑'),('TPE2','专辑歌手'),('TRCK','2/12'),('TPOS','1/2'),('TDRC','2024')])
    n=len(data); sync=bytes([(n>>21)&127,(n>>14)&127,(n>>7)&127,n&127])
    frame=b'\xff\xfb\x90\x00' + bytes(413)
    path.write_bytes(b'ID3\x03\0\0'+sync+data+frame*10)


def make_flac(path):
    stream=bytearray(34); stream[0:2]=(4096).to_bytes(2,'big'); stream[2:4]=(4096).to_bytes(2,'big')
    packed=(48000<<44)|(1<<41)|(23<<36)|96000
    stream[10:18]=packed.to_bytes(8,'big')
    tags=[x.encode() for x in ['TITLE=FLAC 曲目','ARTIST=Artist','ALBUM=Album','DATE=2023']]
    comment=struct.pack('<II',0,len(tags))+b''.join(struct.pack('<I',len(x))+x for x in tags)
    path.write_bytes(b'fLaC'+b'\0'+(34).to_bytes(3,'big')+stream+b'\x84'+len(comment).to_bytes(3,'big')+comment+bytes(400))


def make_wav(path):
    value='WAV title'.encode()+b'\0'
    info=b'INFO'+b'INAM'+struct.pack('<I',len(value))+value+(b'\0' if len(value)%2 else b'')
    chunks=b'LIST'+struct.pack('<I',len(info))+info+b'data'+struct.pack('<I',32)+bytes(32)
    path.write_bytes(b'RIFF'+struct.pack('<I',len(chunks)+4)+b'WAVE'+chunks)


def make_opus(path):
    tags=[x.encode() for x in ['TITLE=Opus title','ARTIST=Opus artist','ALBUM=Opus album']]
    payload=b'OpusTags'+struct.pack('<II',0,len(tags))+b''.join(struct.pack('<I',len(x))+x for x in tags)
    # The existing parser intentionally only needs an Ogg page with lacing.
    header=b'OggS'+bytes(22)+b'\x01'
    path.write_bytes(header+bytes([len(payload)])+payload)


class Tests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.base=Path(self.temp.name); self.root=self.base/'Music'; self.root.mkdir()
        folder=self.root/'中文 [专辑]'; folder.mkdir()
        make_mp3(folder/"歌手 - 曲目'$(echo nope).MP3")
        make_flac(folder/'Track.flac')
        (folder/'Track.LRC').write_text('[00:01.00]歌词', encoding='utf-8')
        png=b'\x89PNG\r\n\x1a\n'+(13).to_bytes(4,'big')+b'IHDR'+(32).to_bytes(4,'big')+(24).to_bytes(4,'big')+bytes(8)
        (folder/'cover.png').write_bytes(png)
        # Ensure SFTP second-resolution timestamps match the desktop reference.
        for item in self.root.rglob('*'): os.utime(item,(1700000000,1700000000))
        target=self.root/'_FakePod/music-index'; target.mkdir(parents=True)
        self.bridge=Bridge(); self.scope='nas:22:'+str(self.root)
        self.output=self.base/'out'
    def scan(self):
        return json.loads(mobile.scan(self.bridge,str(self.root),self.scope,str(self.output)))
    def publish(self):
        return mobile.publish(self.bridge,str(self.root),self.scope,str(self.output))
    def test_binary_and_alias_compatibility(self):
        summary=self.scan()
        rows,_,_=desktop.scan_incremental(self.root,None)
        expected_index, expected_manifest,*_=desktop.build_catalog(rows)
        self.assertEqual((self.output/'music_index_v2.bin').read_bytes(),expected_index)
        self.assertEqual((self.output/'music_manifest_v2.bin').read_bytes(),expected_manifest)
        self.assertEqual((self.output/'fakepod_make_static_track_links.sh').read_bytes(),desktop.build_static_alias_script(rows))
        self.assertEqual(summary['total'],2)
        self.publish()
        for row in rows:
            alias=self.root/'_FakePod/track'/('%016X%s'%(row.track_id,Path(row.path).suffix))
            self.assertTrue(os.path.samefile(alias,self.root/row.path.lstrip('/')))
        self.assertFalse((self.root/'_FakePod/.app-update-lock').exists())
    def test_all_formats_match_desktop(self):
        make_wav(self.root/'Wave.wav'); make_opus(self.root/'Opus.opus')
        for item in self.root.rglob('*'):
            if item.is_file(): os.utime(item,(1700000000,1700000000))
        self.scan()
        rows,_,_=desktop.scan_incremental(self.root,None)
        index,manifest,*_=desktop.build_catalog(rows)
        self.assertEqual((self.output/'music_index_v2.bin').read_bytes(),index)
        self.assertEqual((self.output/'music_manifest_v2.bin').read_bytes(),manifest)
        reference=self.base/'desktop-output'
        subprocess.run([sys.executable,str(Path(desktop.__file__)),str(self.root),str(reference)],check=True,stdout=subprocess.DEVNULL)
        self.assertEqual((self.output/'fakepod_catalog.meta').read_bytes(),(reference/'fakepod_catalog.meta').read_bytes())

    def test_incremental_and_manual_alias_preserved(self):
        self.scan(); self.publish()
        manual=self.root/'_FakePod/track/manual.mp3'
        os.link(next(self.root.rglob('*.MP3')),manual)
        self.output=self.base/'second'
        summary=self.scan()
        self.assertEqual(summary['reused'],2)
        self.assertEqual(summary['added'],0)
        make_mp3(self.root/'新增.mp3','New')
        self.output=self.base/'third'
        summary=self.scan(); self.assertEqual(summary['added'],1)
        self.publish(); self.assertTrue(manual.exists())
        (self.root/'新增.mp3').unlink()
        self.output=self.base/'fourth'
        self.assertEqual(self.scan()['removed'],1)
    def test_snapshot_change_blocks_publication(self):
        self.scan(); make_mp3(self.root/'late.mp3')
        with self.assertRaisesRegex(ValueError,'发生变化'): self.publish()
        self.assertFalse((self.root/'_FakePod/music-index/fakepod_catalog.meta').exists())
    def test_other_publisher_revision_blocks_publication(self):
        self.scan(); (self.root/'_FakePod/music-index/fakepod_catalog.meta').write_text('changed')
        with self.assertRaisesRegex(ValueError,'其他工具'): self.publish()
    def test_revision_rechecked_inside_publish_lock(self):
        self.scan(); self.publish()
        self.output=self.base/'second'; self.scan()
        original=self.bridge.publish
        target=self.root/'_FakePod/music-index/fakepod_catalog.meta'
        def changed(script,stage):
            target.write_text('another publisher committed')
            return original(script,stage)
        self.bridge.publish=changed
        with self.assertRaises(RuntimeError): self.publish()
        self.assertEqual(target.read_text(),'another publisher committed')
        self.assertFalse((self.root/'_FakePod/.app-update-lock').exists())

    def test_rollback_after_partial_publication(self):
        self.scan(); self.publish()
        target=self.root/'_FakePod/music-index'
        before={n:(target/n).read_bytes() for n in mobile.ARTIFACTS}
        source=next(self.root.rglob('*.MP3'))
        old_alias=self.root/'_FakePod/track'/('%016X%s'%(desktop.stable_track_id('/'+source.relative_to(self.root).as_posix()),source.suffix))
        old_inode=old_alias.stat().st_ino
        replacement=self.root/'replacement'; make_mp3(replacement,'Changed title')
        os.replace(replacement,source)
        make_mp3(self.root/'new.mp3')
        new_alias=self.root/'_FakePod/track'/('%016X.mp3'%desktop.stable_track_id('/new.mp3'))
        self.output=self.base/'failed'; self.scan(); self.bridge.fail_publish=True
        with self.assertRaises(RuntimeError): self.publish()
        self.assertEqual(before,{n:(target/n).read_bytes() for n in mobile.ARTIFACTS})
        self.assertEqual(old_alias.stat().st_ino,old_inode)
        self.assertFalse(new_alias.exists())
        self.assertFalse((self.root/'_FakePod/.app-update-lock').exists())
    def test_checkpoint_after_interruption_and_truncated_record(self):
        journal = self.base/'checkpoint'
        original = self.bridge.progress
        def interrupt(message):
            original(message)
            if message.startswith('读取歌曲信息 1/2（'):
                raise RuntimeError('simulated disconnect')
        self.bridge.progress = interrupt
        with self.assertRaisesRegex(RuntimeError, 'disconnect'):
            mobile.scan(self.bridge, str(self.root), self.scope, str(self.output), str(journal))
        self.assertFalse((self.output/'fakepod_catalog.meta').exists())
        with (journal/'scan.jsonl').open('a') as stream: stream.write('{"partial":')
        self.bridge.progress = original
        summary = json.loads(mobile.scan(self.bridge, str(self.root), self.scope, str(self.output), str(journal)))
        self.assertEqual(summary['reused'], 1)
        self.assertEqual(summary['added'], 2)
        reference = self.base/'reference'
        mobile.scan(Bridge(), str(self.root), self.scope, str(reference))
        self.assertEqual((self.output/'music_index_v2.bin').read_bytes(), (reference/'music_index_v2.bin').read_bytes())

    def test_checkpoint_invalidates_changed_audio_and_cover(self):
        journal = self.base/'checkpoint'
        def run():
            return json.loads(mobile.scan(self.bridge, str(self.root), self.scope, str(self.output), str(journal)))
        run()
        self.assertEqual(run()['reused'], 2)
        source = next(self.root.rglob('*.MP3'))
        make_mp3(source, 'Updated and longer title')
        self.assertEqual(run()['reused'], 1)
        cover = next(self.root.rglob('cover.png'))
        os.utime(cover, (1800000000, 1800000000))
        self.assertEqual(run()['reused'], 0)
        other = json.loads(mobile.scan(self.bridge, str(self.root), 'different-scope', str(self.output), str(journal)))
        self.assertEqual(other['reused'], 0)

    def test_case_conflict_rejected(self):
        make_mp3(self.root/'A.mp3'); make_mp3(self.root/'a.mp3')
        with self.assertRaisesRegex(ValueError,'大小写'): self.scan()
    def test_empty_library_not_published(self):
        for p in self.root.rglob('*'):
            if p.is_file(): p.unlink()
        with self.assertRaisesRegex(ValueError,'空曲库'): self.scan()


if __name__=='__main__': unittest.main()
