"""
런처 아이콘(검정 배경 + 흰 글씨 "메모")의 글자 윤곽 벡터를 만든다.

  pip install fonttools
  curl -Lo NotoSansKR.ttf "https://raw.githubusercontent.com/google/fonts/main/ofl/notosanskr/NotoSansKR%5Bwght%5D.ttf"
  python3 tools/make_icon.py NotoSansKR.ttf

글자를 바꾸려면 아래 rows 를 고치면 된다.
"""
import os
import sys

from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen

font = instantiateVariableFont(TTFont(sys.argv[1] if len(sys.argv) > 1 else "NotoSansKR.ttf"), {"wght": 700})
cmap = font.getBestCmap()
glyphs = font.getGlyphSet()
upm = font["head"].unitsPerEm

# 칸 격자로 배치한다. None 은 빈칸. 예) [["조", "상", "원"], ["메", None, "모"]]
rows = [["메", "모"]]
EM = 23.0            # 글자 크기, dp 단위 (108dp 캔버스, 안전 영역은 가운데 지름 66dp 원)
PITCH = EM * 0.92    # 칸 간격 = 글자 폭(920/1000). 글자끼리 붙여서 배치
GAP = 5.0            # 두 줄 사이 간격
scale = EM / upm

# 실제 글자 윤곽 기준으로 세로 가운데 맞춤
def bounds(ch):
    bp = BoundsPen(glyphs)
    glyphs[cmap[ord(ch)]].draw(bp)
    return bp.bounds  # xMin, yMin, xMax, yMax (폰트 단위, y 위로)

line_tops, line_bottoms = [], []
for row in rows:
    bs = [bounds(c) for c in row if c]
    line_tops.append(max(b[3] for b in bs))
    line_bottoms.append(min(b[1] for b in bs))
line_h = [(t - b) * scale for t, b in zip(line_tops, line_bottoms)]
total_h = sum(line_h) + GAP * (len(rows) - 1)  # 줄 사이 간격은 줄이 2개 이상일 때만
top = 54 - total_h / 2
columns = max(len(row) for row in rows)
left = 54 - PITCH * columns / 2

parts = []
y_cursor = top
for r, row in enumerate(rows):
    baseline = y_cursor + line_tops[r] * scale   # 화면 좌표(아래로 +)
    for col, ch in enumerate(row):
        if not ch:
            continue
        g = cmap[ord(ch)]
        adv = glyphs[g].width * scale
        x = left + col * PITCH + (PITCH - adv) / 2
        pen = SVGPathPen(glyphs, ntos=lambda v: ("%.2f" % v).rstrip("0").rstrip("."))
        glyphs[g].draw(TransformPen(pen, (scale, 0, 0, -scale, x, baseline)))
        parts.append(pen.getCommands())
    y_cursor += line_h[r] + GAP

path = " ".join(parts)

def vector(color):
    return f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- "{" / ".join("".join(c or " " for c in row) for row in rows)}" (Noto Sans KR Bold, SIL OFL) 글자 윤곽 -->
    <path
        android:fillColor="{color}"
        android:pathData="{path}" />
</vector>
'''
res = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "drawable") + os.sep
open(res + "ic_launcher_foreground.xml", "w").write(vector("#FFFFFFFF"))
open(res + "ic_launcher_monochrome.xml", "w").write(vector("#FF000000"))
print("path chars:", len(path), "block", left, top, PITCH * columns, total_h)
