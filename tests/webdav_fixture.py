"""Local HTTP fixture for WebDavTransportCheck.kt (not a real NAS)."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, quote, urlparse
from xml.sax.saxutils import escape

DATA = bytes(range(256)) * 390 + bytes(range(160))
NAME = '中文 + #%曲目.mp3'
ROOT = '/share/Music'


def row(path, folder=False):
    resource = '<d:collection/>' if folder else ''
    return ('<d:response><d:href>' + escape(quote(path)) + '</d:href><d:propstat><d:prop>'
            '<d:resourcetype>' + resource + '</d:resourcetype><d:getcontentlength>100000</d:getcontentlength>'
            '<d:getlastmodified>Tue, 14 Nov 2023 22:13:20 GMT</d:getlastmodified>'
            '</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>'
            '<d:propstat><d:prop><d:getcontentlength>999999</d:getcontentlength></d:prop>'
            '<d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>')


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    flaky_seen = False
    def log_message(self, *args): pass
    def reply(self, code, body, headers=()):
        assert self.headers.get('Connection') == 'close'
        assert self.headers.get('Accept-Encoding') == 'identity'
        self.send_response(code)
        self.send_header('Connection', 'close')
        for k,v in headers: self.send_header(k,v)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers(); self.wfile.write(body)
    def do_PROPFIND(self):
        assert int(self.headers.get('Content-Length',0)) == 0
        self.rfile.read(int(self.headers.get('Content-Length',0)))
        path = unquote(urlparse(self.path).path).rstrip('/')
        rows = row(path, path == ROOT)
        if self.headers.get('Depth') == '1':
            rows += row(ROOT+'/'+NAME) + row(ROOT+'/Album', True)
        if path.endswith('/outside'): rows += row('/not-selected/file.mp3')
        self.reply(207, ('<d:multistatus xmlns:d="DAV:">'+rows+'</d:multistatus>').encode())
    def do_GET(self):
        path = unquote(urlparse(self.path).path)
        if path.endswith('/flaky-probe'):
            if not Handler.flaky_seen:
                Handler.flaky_seen=True
                self.send_response(200); self.send_header('Connection','close'); self.send_header('Content-Length','100')
                self.end_headers(); self.wfile.write(b'cut'); self.wfile.flush(); self.close_connection=True
                return
            return self.reply(200,b'probe-ok')
        if path.endswith('/broken-probe'):
            self.send_response(200); self.send_header('Connection','close'); self.send_header('Content-Length','100')
            self.end_headers(); self.wfile.write(b'cut'); self.wfile.flush(); self.close_connection=True
            return
        if path.endswith('/probe'): return self.reply(200,b'probe-ok')
        if not path.endswith(('/'+NAME,'/ignore.mp3','/bad-range.mp3')): return self.reply(404,b'')
        if path.endswith('/ignore.mp3'): return self.reply(200,DATA)
        start,end = map(int,self.headers['Range'].removeprefix('bytes=').split('-'))
        end=min(end,len(DATA)-1)
        header_start = start+1 if path.endswith('/bad-range.mp3') else start
        self.reply(206,DATA[start:end+1],[('Content-Range',f'bytes {header_start}-{end}/{len(DATA)}')])


if __name__ == '__main__':
    server=ThreadingHTTPServer(('127.0.0.1',8765),Handler)
    print('WebDAV test fixture on 127.0.0.1:8765',flush=True)
    server.serve_forever()
