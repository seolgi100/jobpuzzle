# 첫 화면의 퍼즐 조각 모델을 만든다. Blender를 화면 없이 실행:
#   blender -b --factory-startup --python design-test/tools/build_piece.py [-- 글씨체 …]
# index.html 의 조각 윤곽(PIECE_D)과 J 윤곽(J_GLYPHS)을 읽어, 글씨체마다 design-test/models/piece-<글씨체>.glb 를 만든다.
#   body  : 조각. 옆면에는 홈·구슬 띠·오목한 허리가 돌아가는 몰딩이 있고, 앞뒤 면 한가운데에 J가 부드럽게 파여 있음.
#           그늘은 꼭짓점 색으로 구워 넣음. 앞뒤 면의 장식 문양은 모양이 아니라 그림(models/ornament-*.png)으로 입힘
#   inlay : 음각 안쪽 면만 베낀 것. 포인트 색을 켰을 때 그 위에 색을 입히는 데 씀
import bpy, bmesh, math, os, re, sys
from mathutils import Matrix

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, 'models')
html = open(os.path.join(ROOT, 'index.html'), encoding='utf-8').read()
PIECE_D = re.search(r"const PIECE_D = '([^']+)'", html).group(1)
GLYPHS = dict(re.findall(r"'([\w-]+)': \{ label: '[^']*', d: '([^']+)' \}", html))

K = 2 / 148                      # 윤곽 단위 → 무대 단위 (조각 폭 = 2)
OX, OY = 114, 86                 # 회전 중심 (조각 전체의 한가운데)
H, ROUND, WAIST = 13, 1.3, 2.0   # 두께의 절반, 모서리 둥글림, 옆면 허리가 들어간 깊이
LIP, DEPTH = 0.95, 2.8           # 음각 입구가 벌어진 폭, 음각 깊이 (획이 얇아 보이게 좁고 얕게)
J_SCALE, J_DY = 0.92, -4         # J 크기와 위치 보정: 몸통 정중앙(100, 100)에서 살짝 줄이고 위로 올림 (아래 홈과 너무 붙어 보여서)


def flatten(d, n=16):
    """SVG 경로(M H V C Q Z, 절대 좌표)를 점으로 편다."""
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


def area(r):
    return sum(r[i][0] * r[(i + 1) % len(r)][1] - r[(i + 1) % len(r)][0] * r[i][1] for i in range(len(r))) / 2


def ring(points):
    """겹친 점을 빼고, y를 위로 뒤집고, 반시계 방향으로 맞춘 고리."""
    r = []
    for x, y in points:
        p = (x - OX, OY - y)
        if not r or math.dist(p, r[-1]) > 1e-3: r.append(p)
    if math.dist(r[0], r[-1]) < 1e-3: r.pop()
    return r[::-1] if area(r) < 0 else r


def normals(r):
    """각 점에서 고리 바깥을 향하는 방향 (모서리에서는 두 변의 중간, 간격이 일정하도록 길이 보정)."""
    n = len(r); edge = []
    for i, p in enumerate(r):
        q = r[(i + 1) % n]; dx, dy = q[0] - p[0], q[1] - p[1]; l = math.hypot(dx, dy) or 1
        edge.append((dy / l, -dx / l))
    out = []
    for i in range(n):
        a, b = edge[i - 1], edge[i]
        x, y = a[0] + b[0], a[1] + b[1]; l = math.hypot(x, y) or 1; x /= l; y /= l
        m = 1 / max(0.5, x * a[0] + y * a[1])
        out.append((x * m, y * m))
    return out


def push(r, nr, d):
    return [(p[0] + nr[i][0] * d, p[1] + nr[i][1] * d) for i, p in enumerate(r)]


def mirrored(r, cx):
    m = [(2 * cx - x, y) for x, y in r]
    return m[::-1] if area(m) < 0 else m


def glyph_rings(name):
    """글씨체의 J 고리들 (첫 고리가 글자, 나머지는 글자 안의 섬). 크기·위치 보정을 함께 적용."""
    out = []
    for part in GLYPHS[name].split('Z'):
        if not part.strip(): continue
        pts = [tuple(map(float, xy.split(','))) for xy in part.replace('M', '').split()]
        out.append(ring([(100 + (x - 100) * J_SCALE, 100 + (y - 100) * J_SCALE + J_DY) for x, y in pts]))
    return out


def lip_widths(rings):
    """고리마다 (바깥 방향, 돌이 있는 쪽, 점별 입구 폭). 획 사이가 좁은 곳에서는 입구가 서로 겹치지 않게 폭을 줄임."""
    segs = [(r[k], r[(k + 1) % len(r)]) for r in rings for k in range(len(r))]
    out = []
    for n, r in enumerate(rings):
        nr = normals(r); into = 1 if n == 0 else -1; w = []   # 첫 고리는 글자 바깥이 돌, 속 고리(글자 안의 섬)는 안쪽이 돌
        for i, p in enumerate(r):
            l = math.hypot(*nr[i]); dx, dy = into * nr[i][0] / l, into * nr[i][1] / l
            ox, oy = p[0] + dx * 0.05, p[1] + dy * 0.05; reach = 1e9   # 돌 쪽으로 쏜 선이 글자 윤곽에 다시 닿기까지의 거리
            for a, b in segs:
                ex, ey = b[0] - a[0], b[1] - a[1]; den = dx * ey - dy * ex
                if abs(den) < 1e-9: continue
                t = ((a[0] - ox) * ey - (a[1] - oy) * ex) / den; u = ((a[0] - ox) * dy - (a[1] - oy) * dx) / den
                if t > 1e-6 and 0 <= u <= 1: reach = min(reach, t)
            # 돌 쪽으로 오목하게 꺾인 곳에서는 벌어진 입구가 서로 엇갈리므로, 굽은 반지름보다 넓게 벌리지 않음
            a, c = r[i - 1], r[(i + 1) % len(r)]
            ux, uy, vx, vy = p[0] - a[0], p[1] - a[1], c[0] - p[0], c[1] - p[1]
            turn = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
            bend = 0.7 * (math.hypot(ux, uy) + math.hypot(vx, vy)) / 2 / abs(turn) if turn * into < -1e-6 else 1e9
            w.append(min(LIP, 0.36 * reach, bend))
        # 좁은 곳에서 멀어질수록 서서히 넓어지게 함 (폭이 갑자기 변하면 거기서 또 엇갈림)
        k = len(w); step = [math.dist(r[i], r[(i + 1) % k]) for i in range(k)]
        for _ in range(2):
            for i in range(k): w[(i + 1) % k] = min(w[(i + 1) % k], w[i] + 0.7 * step[i])
            for i in range(k - 1, -1, -1): w[i] = min(w[i], w[(i + 1) % k] + 0.7 * step[i])
        out.append((nr, into, w))
    return out


def strip(bm, r, nr, prof):
    """고리를 따라 단면(prof: [바깥으로 민 거리, 높이])을 훑은 띠."""
    rows = [[bm.verts.new((p[0] + nr[i][0] * d, p[1] + nr[i][1] * d, z)) for i, p in enumerate(r)] for d, z in prof]
    n = len(r)
    for a, b in zip(rows, rows[1:]):
        for i in range(n): bm.faces.new((a[i], b[i], b[(i + 1) % n], a[(i + 1) % n]))


def fill(bm, loops, z):
    """평평한 면. loops 의 첫 고리가 바깥, 나머지는 구멍."""
    edges = []
    for r in loops:
        vs = [bm.verts.new((x, y, z)) for x, y in r]
        edges += [bm.edges.new((vs[i], vs[(i + 1) % len(vs)])) for i in range(len(vs))]
    bmesh.ops.triangle_fill(bm, edges=edges, use_beauty=True)


def side_profile():
    """옆면 단면 (앞 → 뒤). 둥근 모서리 → 가는 홈 → 볼록한 구슬 띠 → 오목한 허리 → 다시 구슬 띠 → 홈 → 둥근 모서리."""
    half = [(-ROUND * (1 - math.sin(i / 7 * math.pi / 2)), H - ROUND * (1 - math.cos(i / 7 * math.pi / 2))) for i in range(8)]
    z = H - ROUND
    half.append((0, z - 0.6)); z -= 0.6                                    # 모서리 아래 좁은 평면
    for i in range(1, 7): half.append((-0.85 * math.sin(math.pi * i / 6) ** 2, z - 1.2 * i / 6))     # 가는 홈
    z -= 1.2
    for i in range(1, 9):                                                  # 볼록한 구슬 띠 (반원 단면)
        u = i / 8; half.append((0.8 * math.sqrt(max(0, 1 - (2 * u - 1) ** 2)), z - 2.4 * u))
    z -= 2.4
    for i in range(1, 9):                                                  # 오목한 허리
        zz = z * (1 - i / 8); half.append((-WAIST * (1 - (zz / z) ** 2) ** 2, zz))
    return half + [(d, -zz) for d, zz in half[-2::-1]]


def build(glyph):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    piece = ring(flatten(PIECE_D)); pn = normals(piece)
    rings = glyph_rings(glyph)
    sides = [(1, rings), (-1, [mirrored(r, 100 - OX) for r in rings])]   # 뒷면은 좌우를 뒤집어야 뒤에서 바로 읽힘

    # 1) 면을 직접 만든다: 옆면 띠, 앞뒤 평면(글자 자리는 뚫림), 음각 벽, 음각 바닥
    bm = bmesh.new()
    strip(bm, piece, pn, side_profile())
    face = push(piece, pn, -ROUND)
    for side, rs in sides:
        lips = []
        for r, (nr, into, w) in zip(rs, lip_widths(rs)):
            rows = []
            for j in range(10):   # 입구에서도 바닥에서도 완만한 S자 단면: 턱 없이 부드럽게 들어감
                t = j / 9; z = side * (H - DEPTH * t * t * (3 - 2 * t))
                rows.append([bm.verts.new((p[0] + nr[i][0] * into * w[i] * (1 - t), p[1] + nr[i][1] * into * w[i] * (1 - t), z)) for i, p in enumerate(r)])
            for a, b in zip(rows, rows[1:]):
                for i in range(len(r)): bm.faces.new((a[i], b[i], b[(i + 1) % len(r)], a[(i + 1) % len(r)]))
            lips.append([(p[0] + nr[i][0] * into * w[i], p[1] + nr[i][1] * into * w[i]) for i, p in enumerate(r)])
        fill(bm, [face, lips[0]], side * H)                      # 앞(뒤) 평면
        for island in lips[1:]: fill(bm, [island], side * H)     # 글자 안의 섬
        fill(bm, rs, side * (H - DEPTH))                         # 음각 바닥
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-4)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    open_edges = sum(1 for e in bm.edges if len(e.link_faces) != 2)
    me = bpy.data.meshes.new('body'); bm.to_mesh(me); bm.free()
    body = bpy.data.objects.new('body', me); bpy.context.collection.objects.link(body)

    # 2) 매끈한 곡면은 매끈하게, 꺾인 곳은 또렷하게
    for p in me.polygons: p.use_smooth = True
    me.set_sharp_from_angle(angle=math.radians(38))
    bpy.context.view_layer.objects.active = body; body.select_set(True)
    wn = body.modifiers.new('normals', 'WEIGHTED_NORMAL'); wn.keep_sharp = True
    bpy.ops.object.modifier_apply(modifier=wn.name)

    # 3) 그늘(주변 가림)을 꼭짓점 색으로 구움: 음각 안쪽, 홈 둘레, 옆면 홈이 자연스럽게 어두워짐
    col = me.color_attributes.new('Col', 'FLOAT_COLOR', 'POINT')
    me.color_attributes.active_color = col; me.color_attributes.render_color_index = 0
    me.materials.append(bpy.data.materials.new('stone'))
    scene.world = bpy.data.worlds.new('world'); scene.world.light_settings.distance = 9
    scene.render.engine = 'CYCLES'; scene.cycles.device = 'CPU'; scene.cycles.samples = 96
    scene.render.bake.target = 'VERTEX_COLORS'
    bpy.ops.object.bake(type='AO')
    for vert, c in zip(me.vertices, col.data):
        if abs(abs(vert.co.z) - H) < 1e-3: v = 1.0   # 앞뒤 평면은 그늘 없음 (큰 삼각형을 타고 얼룩이 번지지 않게)
        else: v = 0.22 + 0.78 * min(1.0, c.color[0]) ** 0.8   # 너무 새까매지지 않게 조금 풀어 줌
        c.color = (v, v, v, 1)

    # 4) 문양을 입힐 좌표: 조각 윤곽이 그려진 200×200 판을 앞에서 그대로 비춘 것
    uv = me.uv_layers.new(name='UVMap')
    for loop in me.loops:
        co = me.vertices[loop.vertex_index].co
        uv.data[loop.index].uv = ((co.x + OX) / 200, 1 - (OY - co.y) / 200)

    # 5) 포인트 색을 켰을 때 음각 안쪽을 덮는 색면: 음각 면(입구 바로 아래부터 바닥까지)을 그대로 베낌
    xs = [p[0] for _, rs in sides for r in rs for p in r]; ys = [p[1] for _, rs in sides for r in rs for p in r]
    box = (min(xs) - LIP - 0.5, min(ys) - LIP - 0.5, max(xs) + LIP + 0.5, max(ys) + LIP + 0.5)
    bm = bmesh.new(); bm.from_mesh(me)
    inside = lambda f: all(abs(v.co.z) <= H - 0.3 and box[0] <= v.co.x <= box[2] and box[1] <= v.co.y <= box[3] for v in f.verts)
    bmesh.ops.delete(bm, geom=[f for f in bm.faces if not inside(f)], context='FACES')
    ime = bpy.data.meshes.new('inlay'); bm.to_mesh(ime); bm.free()
    inlay = bpy.data.objects.new('inlay', ime); bpy.context.collection.objects.link(inlay)
    while ime.color_attributes: ime.color_attributes.remove(ime.color_attributes[0])

    # 6) 무대 단위로 줄여서 내보냄 (축은 그대로: 앞면이 +z)
    for ob in (body, inlay):
        ob.data.transform(Matrix.Scale(K, 4)); ob.data.update(); ob.select_set(True)
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f'piece-{glyph}.glb')
    bpy.ops.export_scene.gltf(filepath=path, export_format='GLB', use_selection=True, export_yup=False,
                              export_apply=True, export_normals=True, export_texcoords=True,
                              export_materials='NONE', export_vertex_color='ACTIVE',
                              export_active_vertex_color_when_no_material=True,
                              export_draco_mesh_compression_enable=True, export_draco_mesh_compression_level=6,
                              export_draco_position_quantization=14, export_draco_normal_quantization=10,
                              export_draco_color_quantization=10, export_draco_texcoord_quantization=12)
    print(f'[piece] {glyph}: {len(me.vertices)} verts, {len(me.polygons)} faces, open edges {open_edges}, {os.path.getsize(path) // 1024} KB')


if __name__ == '__main__':
    asked = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
    for name in (asked or GLYPHS):
        build(name)
