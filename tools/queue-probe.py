#!/usr/bin/env python3
"""
排队行为实测脚本。

对运行中的中间件做三组并发实验，用真实 HTTP 请求验证调度语义：

  实验 1  同会话并发    —— 同一 x-session-id 打 N 个请求，只能 1 个进上游，
                          其余必须拿到 503 + Retry-After（不是 429）。
  实验 2  不同会话并发  —— 不同 session 但总量超过后端容量时，放行数 ≤ capacity。
  实验 3  排队后重试    —— 收到 503 后按 Retry-After 等待再发，最终应成功。

用法：
    python tools/queue-probe.py [gateway] [key] [--probe]

    gateway 默认 http://127.0.0.1:8090
    key     默认 sk-ac-e2e-fake-0001
"""
import argparse
import io
import json
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request

def post(gateway, key, session, body, timeout=120):
    """发一次非流式请求，返回 (status, retry_after, reason, elapsed_ms)。"""
    req = urllib.request.Request(
        f"{gateway}/v1/chat/completions",
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {key}",
            "x-session-id": session,
            "Content-Type": "application/json",
        },
        method="POST",
    )
    start = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            resp.read()
            return resp.status, None, None, (time.time() - start) * 1000
    except urllib.error.HTTPError as e:
        ra = e.headers.get("Retry-After")
        reason = e.headers.get("X-Queue-Reason")
        try:
            e.read()
        except Exception:
            pass
        return e.code, ra, reason, (time.time() - start) * 1000


def experiment_same_session(gateway, key, concurrency=6):
    """同会话并发：只应放行 1 个。"""
    print(f"\n=== 实验 1：同一会话并发 {concurrency} 个请求 ===")
    print("预期：1 个 200，其余全部 503（带 Retry-After 与 X-Queue-Reason: session_busy）")
    results = [None] * concurrency

    def worker(idx):
        results[idx] = post(gateway, key, "probe-same-session",
                            {"model": "qwen-fake", "messages": [{"role": "user", "content": "hi"}]})

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(concurrency)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    ok = [r for r in results if r and r[0] == 200]
    rejected = [r for r in results if r and r[0] == 503]
    other = [r for r in results if r and r[0] not in (200, 503)]

    print(f"  200 放行 : {len(ok)}")
    print(f"  503 排队 : {len(rejected)}")
    if other:
        print(f"  其它状态 : {[(r[0]) for r in other]}")

    if rejected:
        ras = [r[1] for r in rejected if r[1]]
        reasons = {r[2] for r in rejected if r[2]}
        print(f"  Retry-After 示例: {ras[:3]}")
        print(f"  X-Queue-Reason  : {reasons}")

    passed = len(ok) == 1 and len(rejected) == concurrency - 1
    print(f"  结论：{'PASS 会话严格串行' if passed else 'FAIL 不符合预期'}")
    return passed


def experiment_capacity(gateway, key, concurrency=8):
    """不同会话并发：放行数不得超过后端容量（测试库里 capacity=2）。"""
    print(f"\n=== 实验 2：{concurrency} 个不同会话并发（后端容量 2）===")
    print("预期：放行数 ≤ 2，其余 503（reason 为 backend_full 或 weight_round_wait）")
    results = [None] * concurrency

    def worker(idx):
        results[idx] = post(gateway, key, f"probe-cap-{idx}",
                            {"model": "qwen-fake", "messages": [{"role": "user", "content": "hi"}]})

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(concurrency)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    ok = [r for r in results if r and r[0] == 200]
    rejected = [r for r in results if r and r[0] == 503]
    reasons = {}
    for r in rejected:
        reasons[r[2]] = reasons.get(r[2], 0) + 1

    print(f"  200 放行 : {len(ok)}")
    print(f"  503 排队 : {len(rejected)}")
    print(f"  拒绝原因 : {reasons}")

    # 容量 2：同一瞬间最多 2 个在途。测试里每个请求要 0.9s，8 个并发必然有拒绝。
    passed = len(ok) <= 2 and len(rejected) > 0
    print(f"  结论：{'PASS 未超容量' if passed else 'FAIL 不符合预期'}")
    return passed


def experiment_retry_after(gateway, key):
    """收到 503 后按 Retry-After 等待重试，最终应成功。"""
    print("\n=== 实验 3：收到 503 后按 Retry-After 重试 ===")
    print("预期：第一次被拒并给出 Retry-After，等待后重试成功（200）")

    # 先占住一个会话
    holder_status = {}

    def holder():
        holder_status["r"] = post(gateway, key, "probe-retry",
                                  {"model": "qwen-fake", "messages": [{"role": "user", "content": "hold"}]})

    t = threading.Thread(target=holder)
    t.start()
    time.sleep(0.3)   # 让它先进入上游

    status, retry_after, reason, _ = post(gateway, key, "probe-retry",
                                          {"model": "qwen-fake", "messages": [{"role": "user", "content": "second"}]})
    print(f"  第二个请求: status={status} Retry-After={retry_after} reason={reason}")

    if status != 503:
        print("  FAIL 预期被拒，实际未拒绝（可能是并发时序未对上）")
        t.join()
        return False

    wait_s = float(retry_after) if retry_after else 2.0
    print(f"  按 Retry-After 等待 {wait_s}s ...")
    t.join()                      # 等占位者结束
    time.sleep(wait_s)

    status2, _, _, ms = post(gateway, key, "probe-retry",
                             {"model": "qwen-fake", "messages": [{"role": "user", "content": "retry"}]})
    print(f"  重试结果: status={status2} 耗时={ms:.0f}ms")
    passed = status2 == 200
    print(f"  结论：{'PASS 重试后成功' if passed else 'FAIL 不符合预期'}")
    return passed


def main():
    # Windows 控制台默认 GBK，中文会乱码甚至崩；显式切 UTF-8
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("gateway", nargs="?", default="http://127.0.0.1:8090")
    ap.add_argument("key", nargs="?", default="sk-ac-e2e-fake-0001")
    ap.add_argument("--concurrency", type=int, default=6)
    args = ap.parse_args()

    print(f"网关: {args.gateway}")
    print(f"Key : {args.key[:14]}...")

    results = []
    results.append(experiment_same_session(args.gateway, args.key, args.concurrency))
    results.append(experiment_capacity(args.gateway, args.key, args.concurrency + 2))
    results.append(experiment_retry_after(args.gateway, args.key))

    print("\n" + "=" * 50)
    print(f"总结果：{sum(results)}/{len(results)} 组通过")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
