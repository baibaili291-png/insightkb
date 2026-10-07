#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
InsightKB 检索评测脚本。

作用：把"混合检索到底有没有用"变成一组能复现的数字。

前置条件：
  1. 应用已启动（默认 http://localhost:8080，可用 --base 指定）
  2. 评测集涉及的两份文档已上传：samples/设备运维手册-X200.md、test-library.md

用法：
  python eval/run_eval.py --base http://localhost:52113 --topk 5

输出：向量路 / 关键词路 / 融合路各自的命中率，以及"只有关键词路捞回来"的条数。
这些数字就是简历上"召回率从 X% 提升到 Y%"的来源 —— 必须自己跑，不要编。

评测集在 eval/questions.json，照格式加到你自己的 30~50 条即可。
"""

import argparse
import json
import os
import sys
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")


def fetch_debug(base, question, topk):
    url = base.rstrip("/") + "/api/retrieval/debug?" + urllib.parse.urlencode(
        {"q": question, "topK": topk}
    )
    with urllib.request.urlopen(url, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))["data"]


def hit(route_results, expected, topk):
    """只要命中的块出现在该路 Top-K 里就算召回成功"""
    top = [(c["docName"], c["index"]) for c in route_results[:topk]]
    return any((e["doc"], e["index"]) in top for e in expected)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080", help="应用地址")
    ap.add_argument("--topk", type=int, default=5, help="Top-K")
    ap.add_argument("--questions", default=os.path.join(os.path.dirname(__file__), "questions.json"))
    args = ap.parse_args()

    with open(args.questions, encoding="utf-8") as f:
        questions = json.load(f)

    stat = {
        "vector": {"hit": 0, "ms": 0},
        "keyword": {"hit": 0, "ms": 0},
        "fused": {"hit": 0, "ms": 0},
    }
    keyword_only = 0
    vector_only = 0
    misses = []

    for item in questions:
        d = fetch_debug(args.base, item["q"], args.topk)
        expected = item["expected"]

        v_hit = hit(d["vector"], expected, args.topk)
        k_hit = hit(d["keyword"], expected, args.topk)
        f_hit = hit(d["fused"], expected, args.topk)

        stat["vector"]["hit"] += int(v_hit)
        stat["keyword"]["hit"] += int(k_hit)
        stat["fused"]["hit"] += int(f_hit)
        stat["vector"]["ms"] += d["vectorMs"]
        stat["keyword"]["ms"] += d["keywordMs"]
        stat["fused"]["ms"] += d["totalMs"]
        keyword_only += d["keywordOnlyCount"]
        vector_only += d["vectorOnlyCount"]

        if not f_hit:
            misses.append(item["q"])

    n = len(questions)
    print(f"\n评测集 {n} 条 · Top-{args.topk} · {args.base}")
    print("-" * 58)
    print(f"{'路由':<12}{'命中':<12}{'命中率':<12}{'平均耗时':<12}")
    for route, label in (("vector", "纯向量"), ("keyword", "纯关键词"), ("fused", "融合 RRF")):
        h = stat[route]["hit"]
        ms = stat[route]["ms"] / n
        print(f"{label:<12}{h}/{n:<10}{h / n * 100:>6.1f}%    {ms:>7.1f} ms")
    print("-" * 58)
    print(f"融合路里只有关键词路召回的片段：{keyword_only} 条（BM25 的补位价值）")
    print(f"融合路里只有向量路召回的片段：{vector_only} 条")
    if misses:
        print(f"\n融合路未命中的 {len(misses)} 条：")
        for q in misses:
            print(f"  - {q}")
    print("\n注意：数字必须是这次真实跑出来的。想对比优化前后，")
    print("改 application.yml 的 insightkb.rag.retrieval-mode 重启后各跑一遍。\n")


if __name__ == "__main__":
    main()
