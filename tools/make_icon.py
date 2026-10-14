#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 YangshiTV 的应用图标（各密度 PNG）。

刻意只用标准库（zlib + struct 手写 PNG），不依赖 Pillow —— 沙箱里不一定装得上。
图案：红底 + 白色电视机身 + 深色屏幕 + 红色播放三角。

用法：python make_icon.py <res 目录的绝对路径>
"""
import os
import struct
import sys
import zlib

# 配色
RED = (217, 58, 50, 255)
WHITE = (255, 255, 255, 255)
SCREEN = (26, 26, 30, 255)
CLEAR = (0, 0, 0, 0)

SS = 3  # 超采样倍数（抗锯齿）


def in_round_rect(x, y, x0, y0, x1, y1, r):
    """点是否落在圆角矩形内"""
    if x < x0 or x > x1 or y < y0 or y > y1:
        return False
    cx = min(max(x, x0 + r), x1 - r)
    cy = min(max(y, y0 + r), y1 - r)
    dx = x - cx
    dy = y - cy
    return dx * dx + dy * dy <= r * r


def in_triangle(px, py, ax, ay, bx, by, cx, cy):
    """点是否落在三角形内（重心坐标法）"""
    v0x, v0y = cx - ax, cy - ay
    v1x, v1y = bx - ax, by - ay
    v2x, v2y = px - ax, py - ay
    dot00 = v0x * v0x + v0y * v0y
    dot01 = v0x * v1x + v0y * v1y
    dot02 = v0x * v2x + v0y * v2y
    dot11 = v1x * v1x + v1y * v1y
    dot12 = v1x * v2x + v1y * v2y
    denom = dot00 * dot11 - dot01 * dot01
    if denom == 0:
        return False
    inv = 1.0 / denom
    u = (dot11 * dot02 - dot01 * dot12) * inv
    v = (dot00 * dot12 - dot01 * dot02) * inv
    return u >= 0.0 and v >= 0.0 and (u + v) <= 1.0


def sample(nx, ny):
    """归一化坐标 (0..1) 处应该是什么颜色"""
    # 电视机身（白色圆角矩形）
    if in_round_rect(nx, ny, 0.11, 0.20, 0.89, 0.70, 0.075):
        # 屏幕（深色圆角矩形）
        if in_round_rect(nx, ny, 0.175, 0.275, 0.825, 0.625, 0.045):
            # 播放三角（红色）
            if in_triangle(nx, ny, 0.44, 0.345, 0.44, 0.555, 0.635, 0.45):
                return RED
            return SCREEN
        return WHITE
    # 底座
    if in_round_rect(nx, ny, 0.36, 0.70, 0.64, 0.775, 0.03):
        return WHITE
    # 背景
    if in_round_rect(nx, ny, 0.0, 0.0, 1.0, 1.0, 0.235):
        return RED
    return CLEAR


def render(size):
    """渲染一张 size x size 的 RGBA 像素矩阵（超采样抗锯齿）"""
    rows = []
    step = 1.0 / (size * SS)
    for y in range(size):
        row = []
        for x in range(size):
            r = g = b = a = 0
            for sy in range(SS):
                ny = (y * SS + sy + 0.5) * step
                for sx in range(SS):
                    nx = (x * SS + sx + 0.5) * step
                    cr, cg, cb, ca = sample(nx, ny)
                    # 预乘算权重，避免边缘出现黑边
                    r += cr * ca
                    g += cg * ca
                    b += cb * ca
                    a += ca
            n = SS * SS
            if a == 0:
                row.append((0, 0, 0, 0))
            else:
                row.append((
                    int(round(r / a)),
                    int(round(g / a)),
                    int(round(b / a)),
                    int(round(a / n)),
                ))
        rows.append(row)
    return rows


def write_png(path, rows):
    h = len(rows)
    w = len(rows[0])
    raw = bytearray()
    for row in rows:
        raw.append(0)  # filter type 0 (None)
        for (r, g, b, a) in row:
            raw += bytes((r, g, b, a))

    def chunk(typ, data):
        out = struct.pack(">I", len(data)) + typ + data
        return out + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)
    return len(png)


DENSITIES = [
    ("mipmap-mdpi", 48),
    ("mipmap-hdpi", 72),
    ("mipmap-xhdpi", 96),
    ("mipmap-xxhdpi", 144),
    ("mipmap-xxxhdpi", 192),
]


def main():
    if len(sys.argv) < 2:
        print("usage: make_icon.py <res_dir>")
        return 1
    res_dir = sys.argv[1]

    for folder, size in DENSITIES:
        rows = render(size)
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            p = os.path.join(res_dir, folder, name)
            n = write_png(p, rows)
            print("%-14s %-22s %4dpx  %6d bytes" % (folder, name, size, n))
    return 0


if __name__ == "__main__":
    sys.exit(main())
