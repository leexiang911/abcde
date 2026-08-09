#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SopCam 电脑端归档工具

手机用数据线连电脑后，把 内部存储/DCIM/SopCam 整个拖到电脑，然后：

    # 只生成索引，不动文件
    python archive_photos.py D:\\检修留档\\SopCam

    # 额外按"步骤/元器件"重新分一份（原文件用硬链接，不占额外空间）
    python archive_photos.py D:\\检修留档\\SopCam --by-step D:\\检修留档\\按元器件

文件名约定（由 App 的 FileNaming.build 产生）：
    03_U7·STM32G474·Pin12-15_电容鼓包已更换_143052.jpg
    └┬┘ └──────────┬────────┘ └─────┬──────┘ └──┬─┘
   步骤序号     步骤标签           语音备注     时间

目录约定：
    SopCam/20260809/WO-20260809-017_SN12345/*.jpg
"""

from __future__ import annotations

import argparse
import csv
import os
import re
import sys
from dataclasses import dataclass, asdict
from datetime import datetime
from pathlib import Path

IMG_EXT = {".jpg", ".jpeg", ".png"}
# 03_标签_[备注_]HHMMSS  或  FREE_备注_HHMMSS
NAME_RE = re.compile(r"^(?P<idx>\d{2}|FREE)_(?P<rest>.+)_(?P<time>\d{6})$")


@dataclass
class Shot:
    date: str
    work_order: str
    serial_no: str
    step_index: str
    step_label: str
    note: str
    shot_time: str
    file_name: str
    rel_path: str
    size_kb: int


def parse_folder(folder: Path) -> tuple[str, str]:
    """WO-20260809-017_SN12345 -> ('WO-20260809-017', 'SN12345')"""
    parts = folder.name.split("_", 1)
    return parts[0], (parts[1] if len(parts) > 1 else "")


def parse_name(stem: str) -> tuple[str, str, str, str]:
    m = NAME_RE.match(stem)
    if not m:
        return "", stem, "", ""
    idx = m.group("idx")
    rest = m.group("rest")
    # 步骤标签里用 · 连接位号/型号/针脚，语音备注是后面用 _ 接的部分
    if "_" in rest:
        label, note = rest.split("_", 1)
    else:
        label, note = rest, ""
    return idx, label, note.replace("_", " "), m.group("time")


def scan(root: Path) -> list[Shot]:
    shots: list[Shot] = []
    for path in sorted(root.rglob("*")):
        if path.suffix.lower() not in IMG_EXT or not path.is_file():
            continue
        folder = path.parent
        day = folder.parent.name if folder.parent != root else ""
        wo, sn = parse_folder(folder)
        idx, label, note, t = parse_name(path.stem)
        shots.append(Shot(
            date=day,
            work_order=wo,
            serial_no=sn,
            step_index=idx,
            step_label=label,
            note=note,
            shot_time=t,
            file_name=path.name,
            rel_path=str(path.relative_to(root)),
            size_kb=round(path.stat().st_size / 1024),
        ))
    return shots


def write_index(shots: list[Shot], out: Path) -> None:
    # utf-8-sig：Excel 直接双击打开中文不乱码
    with out.open("w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=list(asdict(shots[0]).keys()) if shots else
                           [f.name for f in Shot.__dataclass_fields__.values()])
        w.writeheader()
        for s in shots:
            w.writerow(asdict(s))


def safe(name: str) -> str:
    return re.sub(r'[\\/:*?"<>|]', "", name).strip() or "未分类"


def mirror_by_step(shots: list[Shot], root: Path, dest: Path) -> int:
    """按元器件/步骤建一份平行视图。优先硬链接，跨盘则回退到复制。"""
    import shutil
    n = 0
    for s in shots:
        bucket = dest / safe(s.step_label or "自由拍摄")
        bucket.mkdir(parents=True, exist_ok=True)
        src = root / s.rel_path
        # 加工单号前缀，避免不同工单同名覆盖
        target = bucket / f"{safe(s.work_order)}_{s.file_name}"
        if target.exists():
            continue
        try:
            os.link(src, target)
        except OSError:
            shutil.copy2(src, target)
        n += 1
    return n


def summarize(shots: list[Shot]) -> None:
    from collections import Counter
    by_wo = Counter(s.work_order for s in shots)
    unparsed = sum(1 for s in shots if not s.shot_time)
    print(f"共 {len(shots)} 张，涉及 {len(by_wo)} 个工单")
    for wo, c in by_wo.most_common(10):
        print(f"  {wo:<28} {c:>4} 张")
    if unparsed:
        print(f"提示：{unparsed} 张文件名不符合命名约定，已按原名收录", file=sys.stderr)


def main() -> int:
    ap = argparse.ArgumentParser(description="扫描 SopCam 导出目录并生成索引")
    ap.add_argument("root", type=Path, help="SopCam 目录")
    ap.add_argument("--index", type=Path, default=None, help="索引 CSV 输出路径")
    ap.add_argument("--by-step", type=Path, default=None, help="按元器件建立平行视图的目录")
    args = ap.parse_args()

    if not args.root.is_dir():
        print(f"目录不存在：{args.root}", file=sys.stderr)
        return 1

    shots = scan(args.root)
    if not shots:
        print("没扫到图片。确认路径指向的是 SopCam 目录本身。", file=sys.stderr)
        return 1

    index = args.index or (args.root / f"索引_{datetime.now():%Y%m%d}.csv")
    write_index(shots, index)
    summarize(shots)
    print(f"索引已写入 {index}")

    if args.by_step:
        n = mirror_by_step(shots, args.root, args.by_step)
        print(f"按元器件视图已建立 {n} 个条目 -> {args.by_step}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
