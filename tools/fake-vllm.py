#!/usr/bin/env python3
"""
假 vLLM —— 本地无 GPU 联调用的 OpenAI 兼容桩服务。

只服务三件事，够中间件把「鉴权 → 准入 → 转发 → 流式回传」整条链路跑通：

  POST /v1/chat/completions
      · stream=false（或缺省）→ 返回标准 chat.completion JSON
      · stream=true           → 返回标准 chat.completion.chunk 的 SSE 序列
  GET  /v1/models             → 返回模型列表
  GET  /health                → 200，给后台「健康检查」按钮用

同时把每次收到的 x-session-id 与请求体打到 stdout，方便肉眼核对中间件
是否真的把会话头透传到了上游（这是「同会话串行」的前置条件）。

用法：
    python tools/fake-vllm.py [port]      # 默认 59998

    # 然后在管理后台「GPU 后端管理」里加一条 base_url = http://127.0.0.1:59998

注意：这是桩，不是真实推理。返回内容是固定的，但**协议格式与 vLLM 一致**，
所以能用来验证转发、流式、503 排队、会话头等所有中间件行为。
"""
import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 59998

# 逐块之间的延迟：用来证明「流式真的逐块到达」而不是攒完再发
CHUNK_DELAY = float(
    # 环境变量优先（便于压测调参）
    __import__("os").environ.get("FAKE_VLLM_CHUNK_DELAY", "0.3")
)
# 单请求耗时下限：模拟真实生成，让「同会话串行」的 503 有时间窗能被观测到
STREAM_HOLD = float(
    __import__("os").environ.get("FAKE_VLLM_STREAM_HOLD", "0.9")
)

MODEL = "qwen-fake"
TOKENS = ["你", "好", "，", "世", "界"]


def _emit(line):
    """带时间戳打印，便于和客户端时序对照。"""
    print(f"[fake-vllm {time.strftime('%H:%M:%S')}] {line}", flush=True)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    # -------------------------------------------------- GET
    def do_GET(self):
        if self.path.startswith("/v1/models"):
            self._json({
                "object": "list",
                "data": [{"id": MODEL, "object": "model", "owned_by": "fake-vllm"}],
            })
        elif self.path.startswith("/health"):
            self._json({"status": "ok"})
        else:
            self._json({"error": {"message": "not found"}}, code=404)

    # -------------------------------------------------- POST
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b""
        session = self.headers.get("x-session-id")
        try:
            payload = json.loads(raw.decode("utf-8")) if raw else {}
        except Exception:
            payload = {"_parse_error": True}

        # 关键核对点：会话头是否透传、body 是否原样到达
        _emit(f"POST {self.path} x-session-id={session} "
              f"stream={payload.get('stream')} body_bytes={len(raw)}")

        if not self.path.startswith("/v1/chat/completions"):
            self._json({"error": {"message": "unsupported path"}}, code=404)
            return

        if payload.get("stream"):
            self._sse(payload)
        else:
            self._completion(payload)

    # -------------------------------------------------- 非流式
    def _completion(self, payload):
        time.sleep(STREAM_HOLD)   # 占住槽位，制造可观测的串行窗口
        content = "".join(TOKENS)
        self._json({
            "id": f"chatcmpl-fake-{int(time.time())}",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": payload.get("model", MODEL),
            "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": content},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 10,
                      "completion_tokens": len(TOKENS),
                      "total_tokens": 10 + len(TOKENS)},
        })

    # -------------------------------------------------- 流式（SSE）
    def _sse(self, payload):
        cid = f"chatcmpl-fake-{int(time.time())}"
        created = int(time.time())
        model = payload.get("model", MODEL)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()

        def chunk(delta, finish=None):
            body = {
                "id": cid, "object": "chat.completion.chunk",
                "created": created, "model": model,
                "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
            }
            self.wfile.write(f"data: {json.dumps(body, ensure_ascii=False)}\n\n".encode())
            self.wfile.flush()
            _emit(f"  → send delta={json.dumps(delta, ensure_ascii=False)} "
                  f"at {time.strftime('%H:%M:%S')}")

        # 首块带 role
        chunk({"role": "assistant", "content": ""})
        for tok in TOKENS:
            time.sleep(CHUNK_DELAY)
            chunk({"content": tok})
        chunk({}, finish="stop")
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()
        _emit("  → send [DONE]")

    # -------------------------------------------------- helpers
    def _json(self, obj, code=200):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass   # 用我们自己的 _emit，静音默认 access log


def main():
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    server.daemon_threads = True
    _emit(f"listening on http://127.0.0.1:{PORT}  "
          f"(chunk_delay={CHUNK_DELAY}s hold={STREAM_HOLD}s)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
