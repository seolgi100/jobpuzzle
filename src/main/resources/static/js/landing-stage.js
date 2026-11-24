// 메인 첫 화면 3D 무대: 어둠 속 조각 하나 (기준: jobpuzzle-impeccable/design-test/index.html).
// 조각은 Blender로 만든 모델(images/landing/piece-carattere.glb, 앞뒤 면에 J 음각)을 불러온다.
// WebGL이나 모델을 못 쓰면 index.html의 평면 SVG 조각이 그대로 남는다.
import * as THREE from 'three';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { DRACOLoader } from 'three/addons/loaders/DRACOLoader.js';

const heroEl = document.getElementById('hero');
const slot = document.getElementById('exhibit');
const canvas = document.querySelector('#stage canvas');
const root = document.documentElement;
const still = matchMedia('(prefers-reduced-motion: reduce)').matches;
const asset = name => new URL(`../images/landing/${name}`, import.meta.url).href;

let renderer = null;
try { renderer = new THREE.WebGLRenderer({ canvas, antialias: true, powerPreference: 'high-performance' }); } catch (e) {}

if (renderer) {
  const ROOM = 0x0B0D11;    // --room 과 같은 값: 캔버스가 첫 화면 바탕에 그대로 이어짐
  const FLOOR = -1.7;       // 조각 중심에서 바닥까지 (조각 폭 = 2)
  const AUTO = still ? 0 : 0.26;   // 평소 회전 속도 (rad/s), 한 바퀴 약 24초
  const SLANT = 0.3, BEAM_W = 3.5, BEAM_X = -0.55;   // 빛줄기: 기울기, 폭, 바닥에 닿는 자리

  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1;
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(ROOM);
  scene.environment = new THREE.PMREMGenerator(renderer).fromScene(new RoomEnvironment(), 0.04).texture;

  const camera = new THREE.PerspectiveCamera(24, 1, 0.1, 100);
  camera.position.set(0, 1.5, 12);
  camera.lookAt(0, 0, 0);
  const FRAME = 2 * camera.position.length() * Math.tan(THREE.MathUtils.degToRad(12));

  const rig = new THREE.Group();     // 무대 전체. 화면 크기에 맞춰 통째로 크기 조절
  const pivot = new THREE.Group();   // 떠서 도는 조각
  scene.add(rig); rig.add(pivot);

  // ══ 재질 ══ 흰 유광 위로 보는 각도에 따라 옅은 무지갯빛. 장식 문양은 높낮이 그림(양각)
  let dirty = true, ready = false;
  const ornament = new THREE.TextureLoader().load(asset('ornament.png'), () => { dirty = true; });
  ornament.flipY = false; ornament.anisotropy = 8;
  const stoneMat = new THREE.MeshPhysicalMaterial({
    vertexColors: true, bumpMap: ornament, color: 0xCDD2DA, metalness: 0, roughness: 0.1, clearcoat: 1, clearcoatRoughness: 0.04, envMapIntensity: 0.55,
    iridescence: 1, iridescenceIOR: 1.35, iridescenceThicknessRange: [180, 520],
    bumpScale: -1.1   // 음수: 그림의 어두운 선이 솟아오름
  });
  // J 음각 안쪽의 델프트 블루: 빛을 되비추지 않는 무광
  const pointMat = new THREE.MeshStandardMaterial({ roughness: 1, metalness: 0, envMapIntensity: 0.12,
    polygonOffset: true, polygonOffsetFactor: -2, polygonOffsetUnits: -2 });
  pointMat.color.set(getComputedStyle(root).getPropertyValue('--point').trim() || '#3A62A8');

  const piece = new THREE.Mesh(undefined, stoneMat);
  piece.castShadow = true;
  const inlay = new THREE.Mesh(undefined, pointMat);
  pivot.add(piece, inlay);

  new GLTFLoader()
    .setDRACOLoader(new DRACOLoader().setDecoderPath('https://cdn.jsdelivr.net/npm/three@0.160.0/examples/jsm/libs/draco/gltf/'))
    .loadAsync(asset('piece-carattere.glb'))
    .then(f => {
      piece.geometry = f.scene.getObjectByName('body').geometry;
      inlay.geometry = f.scene.getObjectByName('inlay').geometry;
      ready = dirty = true;
    })
    .catch(() => { /* 모델을 못 불러오면 평면 SVG 조각을 그대로 둔다 */ });

  // ══ 조명 ══ 위에서 떨어지는 흰 주조명 + 뒤쪽 가장자리 빛 + 약한 정면 빛
  const key = new THREE.SpotLight(0xffffff, 2.4, 0, 0.36, 0.9, 0);
  key.position.set(2.2, 9, 2.6);
  key.target.position.set(0, FLOOR, 0);
  key.castShadow = true;
  key.shadow.mapSize.set(256, 256);
  key.shadow.camera.near = 4; key.shadow.camera.far = 18;
  key.shadow.bias = -0.0005; key.shadow.normalBias = 0.02;
  const rim = new THREE.DirectionalLight(0xdfe8ff, 1.1);
  rim.position.set(-5, 2.5, -6);
  const fill = new THREE.DirectionalLight(0xffffff, 0.35);
  fill.position.set(-3.5, 2, 8);
  rig.add(key, key.target, rim, fill);

  // ── 바닥에 떨어진 빛(물결이 비친 듯 얼룩진 네모)과 조각 그림자
  const pool = new THREE.Mesh(
    new THREE.PlaneGeometry(BEAM_W * 1.25, 3.2),
    new THREE.MeshBasicMaterial({ transparent: true, opacity: 0.3, blending: THREE.AdditiveBlending, depthWrite: false, toneMapped: false,
      map: new THREE.CanvasTexture((() => {
        const c = document.createElement('canvas'); c.width = c.height = 512;
        const g = c.getContext('2d');
        g.filter = 'blur(22px)'; g.fillStyle = 'rgba(255,255,255,.9)'; g.fillRect(64, 96, 384, 320);
        g.globalCompositeOperation = 'destination-out'; g.filter = 'blur(9px)';
        for (let i = 0; i < 46; i++) {
          g.fillStyle = `rgba(0,0,0,${0.25 + Math.random() * 0.4})`;
          g.beginPath(); g.ellipse(40 + Math.random() * 432, 70 + Math.random() * 372, 14 + Math.random() * 38, 5 + Math.random() * 11, Math.random() * 0.6 - 0.3, 0, 7); g.fill();
        }
        return c;
      })()) })
  );
  pool.rotation.x = -Math.PI / 2; pool.position.set(BEAM_X, FLOOR, 0); pool.renderOrder = 1;
  const shadow = new THREE.Mesh(new THREE.PlaneGeometry(10, 10), new THREE.ShadowMaterial({ opacity: 0.36 }));
  shadow.rotation.x = -Math.PI / 2; shadow.position.y = FLOOR + 0.02; shadow.receiveShadow = true; shadow.renderOrder = 2;
  rig.add(pool, shadow);

  // ── 빛줄기: 오른쪽 위 천창에서 비스듬히 쏟아짐. 화면을 향한 면 두 장(조각 뒤·앞)으로 부피감
  const beamGeo = new THREE.BufferGeometry();
  beamGeo.setAttribute('position', new THREE.BufferAttribute(new Float32Array([-BEAM_W / 2, 0, 0, BEAM_W / 2, 0, 0, -BEAM_W / 2, 1, 0, BEAM_W / 2, 1, 0]), 3));
  beamGeo.setAttribute('uv', new THREE.BufferAttribute(new Float32Array([0, 0, 1, 0, 0, 1, 1, 1]), 2));
  beamGeo.setIndex([0, 1, 2, 1, 3, 2]);
  const beamTime = { value: 0 };
  const beam = (z, opacity, seed) => {
    const m = new THREE.Mesh(beamGeo, new THREE.ShaderMaterial({
      uniforms: { uOpacity: { value: opacity }, uSeed: { value: seed }, uTime: beamTime },
      vertexShader: `varying vec2 vUv; void main() { vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }`,
      fragmentShader: `
        uniform float uOpacity, uSeed, uTime; varying vec2 vUv;
        float hash(float n) { return fract(sin(n) * 43758.5453); }
        float noise(float x) { float i = floor(x), f = fract(x); f = f * f * (3.0 - 2.0 * f); return mix(hash(i), hash(i + 1.0), f); }
        void main() {
          float x = vUv.x + uSeed;
          float rays = 0.5 * noise(x * 8.0 + uTime * 0.05) + 0.32 * noise(x * 21.0 - uTime * 0.08) + 0.18 * noise(x * 55.0 + uTime * 0.12);
          rays = smoothstep(0.28, 0.8, rays);
          float edge = smoothstep(0.0, 0.07, vUv.x) * smoothstep(1.0, 0.93, vUv.x);
          float fall = mix(0.2, 1.0, pow(vUv.y, 1.3)) * smoothstep(0.0, 0.04, vUv.y);
          gl_FragColor = vec4(vec3(0.93, 0.96, 1.0), uOpacity * edge * fall * (0.3 + 0.7 * rays));
        }`,
      transparent: true, blending: THREE.AdditiveBlending, depthWrite: false
    }));
    m.position.set(BEAM_X, FLOOR, z); m.renderOrder = 3; m.frustumCulled = false;
    return m;
  };
  rig.add(beam(-1.3, 0.34, 0), beam(1.3, 0.14, 3.7));

  // ── 빛기둥 속에 떠다니는 먼지
  const DUST = 110;
  let beamTop = 9;
  const dustSeed = new Float32Array(DUST * 4);   // 원판 위 x, z, 높이 비율, 속도
  for (let i = 0; i < DUST; i++) {
    const a = Math.random() * Math.PI * 2, r = Math.sqrt(Math.random()) * 0.92;
    dustSeed.set([Math.cos(a) * r, Math.sin(a) * r, Math.random(), 0.006 + Math.random() * 0.014], i * 4);
  }
  const dustGeo = new THREE.BufferGeometry();
  dustGeo.setAttribute('position', new THREE.BufferAttribute(new Float32Array(DUST * 3), 3));
  const dust = new THREE.Points(dustGeo, new THREE.PointsMaterial({ color: 0xffffff, size: 0.036, transparent: true, opacity: 0.4,
    blending: THREE.AdditiveBlending, depthWrite: false }));
  dust.renderOrder = 4; dust.frustumCulled = false;
  rig.add(dust);
  function moveDust(dt) {
    const p = dustGeo.attributes.position;
    for (let i = 0; i < DUST; i++) {
      const h = dustSeed[i * 4 + 2] = (dustSeed[i * 4 + 2] + dustSeed[i * 4 + 3] * dt) % 1;
      p.setXYZ(i, BEAM_X + dustSeed[i * 4] * BEAM_W * 0.5 + SLANT * h * beamTop, FLOOR + h * beamTop, dustSeed[i * 4 + 1] * 0.9);
    }
    p.needsUpdate = true;
  }

  // ── 화면 맞춤: 캔버스는 첫 화면 전체, 조각은 오른쪽 자리(#exhibit) 한가운데
  function layout() {
    const r = heroEl.getBoundingClientRect(), b = slot.getBoundingClientRect();
    const W = Math.max(1, r.width), H2 = Math.max(1, r.height);
    renderer.setPixelRatio(Math.min(devicePixelRatio || 1, 2));
    renderer.setSize(W, H2, false);
    const cx = b.left - r.left + b.width / 2, cy = b.top - r.top + b.height / 2;
    const P = Math.min(b.width * 0.68, b.height * 0.44, 340);   // 조각 폭(px)
    camera.aspect = W / H2;
    camera.setViewOffset(W, H2, W / 2 - cx, H2 / 2 - cy, W, H2);
    camera.updateProjectionMatrix();
    rig.scale.setScalar(P * (FRAME / H2) / 2);
    beamTop = (cy / (P / 2)) * 1.25 + 1.5 - FLOOR;   // 빛줄기가 화면 위쪽 밖까지 닿게
    const bp = beamGeo.attributes.position;
    bp.setXY(2, -BEAM_W / 2 + SLANT * beamTop, beamTop); bp.setXY(3, BEAM_W / 2 + SLANT * beamTop, beamTop); bp.needsUpdate = true;
    dirty = true;
  }
  new ResizeObserver(layout).observe(heroEl);
  layout();

  // ── 움직임: 천천히 떠서 돌고, 잡아서 돌리거나 한 번 눌러서 돌릴 수 있음
  let yaw = -0.5, pitch = 0.06, vel = AUTO, t = 0, last = performance.now();
  let dragging = false, px = 0, py = 0, pt = 0, moved = 0;
  slot.addEventListener('pointerdown', e => {
    dragging = true; moved = 0; px = e.clientX; py = e.clientY; pt = performance.now(); vel = 0;
    slot.setPointerCapture(e.pointerId); slot.classList.add('is-dragging');
  });
  slot.addEventListener('pointermove', e => {
    if (!dragging) return;
    const dx = e.clientX - px, dy = e.clientY - py, now = performance.now();
    yaw += dx * 0.009;
    pitch = THREE.MathUtils.clamp(pitch + dy * 0.006, -0.7, 0.7);
    vel = vel * 0.6 + (dx * 0.009 / Math.max((now - pt) / 1000, 0.008)) * 0.4;
    moved += Math.abs(dx) + Math.abs(dy); px = e.clientX; py = e.clientY; pt = now; dirty = true;
  });
  const release = () => {
    if (!dragging) return;
    dragging = false; slot.classList.remove('is-dragging');
    vel = moved < 5 ? 7 : THREE.MathUtils.clamp(vel, -12, 12);   // 누르기만 하면 한 번 힘차게 돎
  };
  slot.addEventListener('pointerup', release);
  slot.addEventListener('pointercancel', release);

  let raf = 0, visible = true;
  function frame(now) {
    raf = requestAnimationFrame(frame);
    const dt = Math.min((now - last) / 1000, 0.05); last = now;
    if (!dragging) {
      vel += (AUTO - vel) * (1 - Math.exp(-dt * 1.4));
      yaw += vel * dt;
      pitch += (0.06 - pitch) * (1 - Math.exp(-dt * 1.8));
    }
    const moving = !still || dragging || Math.abs(vel) > 0.002 || Math.abs(pitch - 0.06) > 0.002;
    if (!moving && !dirty) return;
    if (!still) t += dt;
    pivot.rotation.set(pitch + Math.sin(t * 0.5) * 0.05, yaw, Math.sin(t * 0.37) * 0.03);
    pivot.position.y = Math.sin(t * 0.9) * 0.08;
    moveDust(still ? 0 : dt);
    beamTime.value = t;
    renderer.render(scene, camera);
    if (dirty) { dirty = false; if (ready) root.classList.add('has-3d'); }
  }
  const run = () => {
    cancelAnimationFrame(raf);
    if (visible && !document.hidden) { last = performance.now(); raf = requestAnimationFrame(frame); }
  };
  new IntersectionObserver(([e]) => { visible = e.isIntersecting; run(); }).observe(heroEl);
  document.addEventListener('visibilitychange', run);
  run();
}
