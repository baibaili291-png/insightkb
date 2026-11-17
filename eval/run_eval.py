#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
InsightKB 检索评测脚本。

作用：把"混合检索到底有没有用"变成一组能复现的数字。

前置条件：
  1. 应用已启动（默认 http://localhost:8080，可用 --base 指定）
  2. 评测集涉及的两份文档已上传：samples/设备运维手册-X200.md、test-library.md

用法：
  python eval/run_eval.py --base http://localhost:52113 --topk 1 3 5

会输出：多档 Top-K 下的命中率、三路的 MRR（首个正确片段的排名倒数），
以及"只有关键词路捞回来"的片段数。

为什么一定要看多档 K：
  语料只有 5 个块时，Top-5 的 Hit 天然是 100%，这个数字没有信息量。
  真正有区分度的是 Top-1 和 MRR —— 前者衡量"答案是不是第一条"，
  后者把"排第 1 还是排第 4"的差异也算进去，不会被 Top-5 抹平。

这些数字就是简历上"召回率从 X% 提升到 Y%"的来源 —— 必须自己跑，不要编。
"""

import argparse
import json
import os
import sys
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

ROUTES = (("vector", "纯向量"), ("keyword", "纯关键词"), ("fused", "融合 RRF"))


def fetch_debug(base, question, topk):
    url = base.rstrip("/") + "/api/retrieval/debug?" + urllib.parse.urlencode(
        {"q": question, "topK": topk}
    )
    with urllib.request.urlopen(url, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))["data"]


def as_pairs(route_results):
    return [(c["docName"], c["index"]) for c in route_results]


def first_rank(pairs, expected):
    """首个命中的排名（1 起）。没命中返回 0，MRR 记 0 分。"""
    wanted = {(e["doc"], e["index"]) for e in expected}
    for i, pair in enumerate(pairs, start=1):
        if pair in wanted:
            return i
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080", help="应用地址")
    ap.add_argument("--topk", type=int, nargs="+", default=[5],
                    help="要评测的 Top-K，可给多档，例如 1 3 5")
    ap.add_argument("--questions", default=os.path.join(os.path.dirname(__file__), "questions.json"))
    args = ap.parse_args()

    ks = sorted(set(args.topk))
    maxk = max(ks)

    with open(args.questions, encoding="utf-8") as f:
        questions = json.load(f)

    hits = {k: {route: 0 for route, _ in ROUTES} for k in ks}
    mrr = {route: 0.0 for route, _ in ROUTES}
    ms = {route: 0.0 for route, _ in ROUTES}
    keyword_only = 0
    vector_only = 0
    misses = []

    for item in questions:
        d = fetch_debug(args.base, item["q"], maxk)
        expected = item["expected"]

        for route, _ in ROUTES:
            pairs = as_pairs(d[route])
            for k in ks:
                if first_rank(pairs[:k], expected):
                    hits[k][route] += 1
            rank = first_rank(pairs, expected)
            mrr[route] += 1.0 / rank if rank else 0.0

        ms["vector"] += d["vectorMs"]
        ms["keyword"] += d["keywordMs"]
        ms["fused"] += d["totalMs"]
        keyword_only += d["keywordOnlyCount"]
        vector_only += d["vectorOnlyCount"]

        if not first_rank(as_pairs(d["fused"])[:maxk], expected):
            misses.append(item["q"])

    n = len(questions)
    print(f"\n评测集 {n} 条 · {args.base}")
    print("-" * 62)
    print(f"{'':<8}" + "".join(f"{label:<12}" for _, label in ROUTES))
    for k in ks:
        cells = "".join(f"{hits[k][route]}/{n} {hits[k][route] / n * 100:>5.1f}%  ".ljust(12)
                        for route, _ in ROUTES)
        print(f"{'Top-' + str(k):<8}{cells}")
    cells = "".join(f"{mrr[route] / n:<12.3f}" for route, _ in ROUTES)
    print(f"{'MRR':<8}{cells}")
    cells = "".join(f"{ms[route] / n:<12.1f}" for route, _ in ROUTES)
    print(f"{'平均耗时':<6}{cells}")
    print("-" * 62)
    print(f"融合路里只有关键词路召回的片段：{keyword_only} 条（BM25 的补位价值）")
    print(f"融合路里只有向量路召回的片段：{vector_only} 条")
    if misses:
        print(f"\n融合路在 Top-{maxk} 内仍未命中的 {len(misses)} 条：")
        for q in misses:
            print(f"  - {q}")
    print("\n注意：数字必须是这次真实跑出来的。想对比优化前后，")
    print("改 application.yml 的 insightkb.rag.retrieval-mode 重启后各跑一遍。\n")


if __name__ == "__main__":
    main()