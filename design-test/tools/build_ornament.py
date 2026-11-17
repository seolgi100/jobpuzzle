# 퍼즐 면의 장식 문양(높이 지도)을 만든다. 흰색 = 면, 어두운 곳이 문양 (페이지에서는 양각으로 도드라지게 씀).
#   python design-test/tools/build_ornament.py        (pillow, shapely 필요)
# index.html 의 조각 윤곽(PIECE_D)을 읽어 design-test/models/ornament.png 를 만든다.
# 구성 (가장자리에만 놓이고, 가운데 J 자리는 비움):
#   테두리   : 윤곽을 따라 도는 두 줄 사이에 "구슬과 마디" 띠
#   모서리   : 모서리를 감싸는 ㄱ·ㄴ 모양 장식. 꼭짓점의 구슬에서 두 변을 따라 뻗은 팔이 끝에서 안쪽으로 말림
#              (바로크 액자 모서리 장식에서 가져옴)
#   돌기     : 돌기 속의 작은 꽃
import math, os, re
from PIL import Image, ImageDraw, ImageFilter
from shapely.geometry import Polygon

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
html = open(os.path.join(ROOT, 'index.html'), encoding='utf-8').read()
PIECE_D = re.search(r"const PIECE_D = '([^']+)'", html).group(1)

SIZE, SS = 1024, 3            # 결과 크기, 그릴 때의 확대 배율 (계단 현상 방지)
U = SIZE * SS / 200           # 윤곽 단위 1 = 몇 픽셀
img = Image.new('L', (SIZE * SS, SIZE * SS), 255)
dr = ImageDraw.Draw(img)


def flatten(d, n=24):
    t = re.findall(r'[MHVCQZ]|-?\d+(?:\.\d+)?', d)
    i, x, y, pts = 0, 0.0, 0.0, []

    def num():
        nonlocal i
        i += 1
        return float(t[i - 1])
    while i < len(t):
        c = t[i]; i += 1
        if c == 'M': x, y = num(), num(); pts.append((x, y))
        elif c == 'H': x = num(); pts.append((x, y))
        elif c == 'V': y = num(); pts.append((x, y))
        elif c == 'C':
            a, b, c2, d2, ex, ey = [num() for _ in range(6)]
            for s in range(1, n + 1):
                u = s / n; v = 1 - u
                pts.append((v ** 3 * x + 3 * v * v * u * a + 3 * v * u * u * c2 + u ** 3 * ex,
                            v ** 3 * y + 3 * v * v * u * b + 3 * v * u * u * d2 + u ** 3 * ey))
            x, y = ex, ey
        elif c == 'Q':
            a, b, ex, ey = [num() for _ in range(4)]
            for s in range(1, n + 1):
                u = s / n; v = 1 - u
                pts.append((v * v * x + 2 * v * u * a + u * u * ex, v * v * y + 2 * v * u * b + u * u * ey))
            x, y = ex, ey
    return pts


# ── 그리기 도구
def stroke(pts, w0, w1=None, ink=0):
    """굵기가 w0 에서 w1 로 변하는 선 (윤곽 단위). 점마다 원을 찍어 끝이 둥글고 매끈함."""
    w1 = w0 if w1 is None else w1
    dense = []
    for a, b in zip(pts, pts[1:]):
        n = max(1, int(math.dist(a, b) * U / 1.5))
        dense += [(a[0] + (b[0] - a[0]) * k / n, a[1] + (b[1] - a[1]) * k / n) for k in range(n)]
    dense.append(pts[-1])
    for k, (x, y) in enumerate(dense):
        r = (w0 + (w1 - w0) * k / max(1, len(dense) - 1)) / 2 * U
        dr.ellipse((x * U - r, y * U - r, x * U + r, y * U + r), fill=ink)


def dot(x, y, r, ink=0):
    dr.ellipse(((x - r) * U, (y - r) * U, (x + r) * U, (y + r) * U), fill=ink)


def blob(x, y, ang, length, width):
    """(x, y)를 중심으로 ang 방향으로 누운 길쭉한 알."""
    ca, sa = math.cos(ang), math.sin(ang)
    pts = [(length / 2 * math.cos(k / 24 * 2 * math.pi), width / 2 * math.sin(k / 24 * 2 * math.pi)) for k in range(24)]
    dr.polygon([((x + px * ca - py * sa) * U, (y + px * sa + py * ca) * U) for px, py in pts], fill=0)


def leaf(x, y, ang, length, width, bend=0.0):
    """(x, y)에서 ang 방향으로 뻗는 잎. 가는 선으로 테두리와 잎맥만 그림. bend 만큼 한쪽으로 휨."""
    ca, sa = math.cos(ang), math.sin(ang)

    def P(t, side):
        w = width / 2 * math.sin(math.pi * t ** 0.7) * side
        px, py = t * length, w + bend * length * t * t
        return (x + px * ca - py * sa, y + px * sa + py * ca)
    ts = [k / 40 for k in range(41)]
    stroke([P(t, 1) for t in ts] + [P(t, -1) for t in ts[::-1]], 0.34)
    stroke([P(t, 0) for t in ts[4:33]], 0.3, 0.14)


def volute(cx, cy, r0, a0, turns, cw, w0=1.0, w1=0.3, start=None):
    """소용돌이: 바깥 끝(반지름 r0, 각도 a0)에서 안으로 말려 들어감. start 가 있으면 거기서 바깥 끝까지 부드럽게 이음."""
    pts = []
    n = int(turns * 90)
    for k in range(n + 1):
        t = k / n; r = r0 * (1 - t) ** 1.25 + 0.5 * t
        a = a0 + (1 if cw else -1) * t * turns * 2 * math.pi
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    if start:
        ex, ey = pts[0]; tx, ty = pts[1][0] - ex, pts[1][1] - ey; l = math.hypot(tx, ty)
        qx, qy = ex - tx / l * math.dist(start, pts[0]) * 0.55, ey - ty / l * math.dist(start, pts[0]) * 0.55
        lead = [((1 - u) ** 2 * start[0] + 2 * (1 - u) * u * qx + u * u * ex,
                 (1 - u) ** 2 * start[1] + 2 * (1 - u) * u * qy + u * u * ey) for u in [k / 30 for k in range(30)]]
        pts = lead + pts
    stroke(pts, w0, w1)
    dot(pts[-1][0], pts[-1][1], 0.6)


def rosette(cx, cy, r, petals=6):
    for k in range(petals):
        a = k * 2 * math.pi / petals - math.pi / 2
        leaf(cx + 0.9 * math.cos(a), cy + 0.9 * math.sin(a), a, r - 0.9, r * 0.66)
    dot(cx, cy, 0.85); dot(cx, cy, 0.36, ink=255)


piece = Polygon(flatten(PIECE_D))
inset = lambda d: piece.buffer(-d, join_style=1, quad_segs=16).exterior

# ── 테두리: 두 줄 사이에 "구슬과 마디" 띠 (길쭉한 알 하나, 가는 마디 둘이 번갈아)
for d, w in ((4.0, 0.8), (9.0, 0.5)): stroke(list(inset(d).coords), w)
mid = inset(6.5)
n = round(mid.length / 2.35)
for k in range(n):
    s = k / n * mid.length
    p, q = mid.interpolate(s), mid.interpolate((s + 0.2) % mid.length)
    a = math.atan2(q.y - p.y, q.x - p.x)
    if k % 3 == 0: blob(p.x, p.y, a, 3.4, 2.0)
    elif k % 3 == 1: blob(p.x + math.cos(a) * 0.55, p.y + math.sin(a) * 0.55, a + math.pi / 2, 2.1, 0.62)
    else: blob(p.x - math.cos(a) * 0.55, p.y - math.sin(a) * 0.55, a + math.pi / 2, 2.1, 0.62)

# ── 모서리: 왼쪽 위(┌)를 그리고, 몸통 한가운데 (100, 100) 기준으로 대칭으로 옮겨 ㄱ·ㄴ·┘ 을 만듦
for sx, sy in ((1, 1), (-1, 1), (1, -1), (-1, -1)):
    T = lambda x, y: (100 + sx * (x - 100), 100 + sy * (y - 100))
    flip = sx * sy

    def V(cx, cy, r0, a0, turns, cwise, start, w0):
        # 대칭으로 옮길 때는 각도와 도는 방향도 함께 뒤집어야 모양이 맞음
        (mx, my), s0 = T(cx, cy), T(*start)
        ax, ay = T(cx + math.cos(a0), cy + math.sin(a0))
        volute(mx, my, r0, math.atan2(ay - my, ax - mx), turns, cwise if flip > 0 else not cwise, w0=w0, start=s0)

    def L(x, y, tx, ty, length, width, bend=0.0):
        (mx, my), (nx, ny) = T(x, y), T(tx, ty)
        leaf(mx, my, math.atan2(ny - my, nx - mx), length, width, bend * flip)

    def S(pts, w0, w1=None): stroke([T(*p) for p in pts], w0, w1)

    # 두 팔: 꼭짓점에서 위쪽 변과 왼쪽 변을 따라 뻗다가 끝에서 안쪽으로 말림 (굵게 시작해 가늘어짐)
    V(67.2, 57.6, 4.6, math.pi * 1.5, 1.45, True, (55.5, 53.0), 1.5)
    V(57.6, 67.2, 4.6, math.pi * 1.0, 1.45, False, (53.0, 55.5), 1.5)
    # 꼭짓점: 두 팔이 만나는 자리에 구슬 하나
    dot(*T(53.3, 53.3), 1.3); dot(*T(53.3, 53.3), 0.5, ink=255)

# ── 돌기 속의 작은 꽃
rosette(100, 26.5, 3.6); rosette(173.5, 100, 3.6)

# 문양의 옆면이 완만하도록 살짝 흐린 뒤 줄여서 저장
img = img.filter(ImageFilter.GaussianBlur(SS * 0.9)).resize((SIZE, SIZE), Image.LANCZOS)
os.makedirs(os.path.join(ROOT, 'models'), exist_ok=True)
out = os.path.join(ROOT, 'models', 'ornament.png')
img.save(out, optimize=True)
print('[ornament]', os.path.getsize(out) // 1024, 'KB')
