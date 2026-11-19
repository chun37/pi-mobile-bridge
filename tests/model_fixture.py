#!/usr/bin/env python3
"""Deterministic OpenAI SSE fixture. Exercises a real Pi TUI without an external model."""
import json
import time
import threading
import uuid
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_): pass
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        messages=body.get('messages',[])
        last=next((m for m in reversed(messages) if m['role']=='user'),{})
        content=last.get('content','')
        if isinstance(content,list):
            text='\n'.join(p.get('text','') for p in content if p.get('type')=='text')
        else: text=content
        tail=messages[-1] if messages else {}
        record={'time':time.time(),'text':text,'lastRole':tail.get('role'),'image':isinstance(content,list) and any(p.get('type')=='image_url' for p in content)}
        with open(ROOT/'evidence/fixture-requests.jsonl','a') as f:f.write(json.dumps(record,ensure_ascii=False)+'\n')
        self.send_response(200);self.send_header('Content-Type','text/event-stream');self.send_header('Cache-Control','no-cache');self.end_headers()
        def send(delta,finish=None):
            event={'id':'fixture','object':'chat.completion.chunk','created':int(time.time()),'model':'bridge-fixture','choices':[{'index':0,'delta':delta,'finish_reason':finish}]}
            self.wfile.write(('data: '+json.dumps(event)+'\n\n').encode());self.wfile.flush()
        try:
            send({'role':'assistant'})
            if 'task-file' in text and tail.get('role')!='tool':
                args=json.dumps({'path':str(ROOT/'evidence/mobile-created.txt'),'content':'created through Android -> SSH -> relay -> extension -> Pi TUI\n'})
                send({'tool_calls':[{'index':0,'id':'fixture_write_'+uuid.uuid4().hex,'type':'function','function':{'name':'write','arguments':args}}]})
                send({},'tool_calls')
            else:
                answer='## 検証応答\n\n**受信しました**: '+text[-500:]+'\n\n日本語とUnicode区切り\u2028継続\u2029終端。'
                if tail.get('role')=='tool':answer+='\n\nファイル作成タスク完了。'
                for i in range(0,len(answer),8):
                    send({'content':answer[i:i+8]});time.sleep(0.15 if 'slow' in text else 0.012)
                send({},'stop')
            self.wfile.write(b'data: [DONE]\n\n');self.wfile.flush()
        except (BrokenPipeError,ConnectionResetError):pass

if __name__=='__main__':
    server=ThreadingHTTPServer(('127.0.0.1',18473),Handler)
    print('fixture ready 127.0.0.1:18473',flush=True)
    server.serve_forever()
