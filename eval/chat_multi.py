#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""多轮对话冒烟测试：验证指代消解与查询改写是否真的生效。

用法：
  python eval/chat_multi.py --base http://localhost:52113

会连问三句，第二、三句故意用带指代的追问（"那它的功率呢"），
看后端能不能结合上一轮上下文答对。
"""

import argparse
import json
import urllib.request

import sys
sys.stdout.reconfigure(encoding="utf-8")


def ask(base, question, conversation_id=None):
    body = json.dumps({"question": question, "conversationId": conversation_id}).encode()
    req = urllib.request.Request(
        base.rstrip("/") + "/api/chat/stream",
        data=body,
        headers={"Content-Type": "application/json"},
    )
    answer, done = "", None
    with urllib.request.urlopen(req, timeout=120) as resp:
        for raw in resp:
            line = raw.decode("utf-8", "replace").strip()
            if not line.startswith("data:"):
                continue
            try:
                p = json.loads(line[5:])
            except Exception:
                continue
            if p.get("type") == "delta":
                answer += p.get("content", "")
            elif p.get("type") == "done":
                done = p
    return answer, done


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:52113")
    args = ap.parse_args()

    script = [
        "Model X200 是什么设备？",
        "那它的额定功率是多少？",
        "供电电压低于多少会出问题？",
    ]
    conv = None
    for q in script:
        answer, done = ask(args.base, q, conv)
        conv = (done or {}).get("conversationId", conv)
        print(f"\n问：{q}")
        if done and done.get("rewritten"):
            print(f"   [查询改写] {done.get('effectiveQuery')}")
        print(f"答：{(answer or '(空)').strip()[:300]}")
        if done:
            print(f"   检索 {done['retrievalMs']}ms | 生成 {done['generationMs']}ms"
                  f" | 会话 {done.get('conversationId')}")


if __name__ == "__main__":
    main()
