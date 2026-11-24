<script setup>
/* ================= v9 · 真 3D 空域场景（Three.js） =================
   风格化地球：Fresnel 大气辉光 + 点阵球面 + 经纬网格；枢纽按 nav.js 真实经纬度落球面；
   航线为抬离球面的 3D 弧线（管几何），在途航班光点沿弧飞行。
   美术原则（用户拍板）：强调必须由数据挣得——
   · 在途弧线自动更亮/更高（主角），其中进度最接近中途的一条为“第一主角”，其光点头用信号琥珀；
   · 待起飞只有暗线 + 始发点静态点，已到达只剩残影线，无任何钦定枢纽的特殊光源；
   · 无在途无异常时场景回到网络全景，不捧任何单点。
   降级：WebGL/初始化失败 → emit('fallback')，父级回退 v8 的 2D SVG 地图，绝不白屏。
   性能：three 随本组件懒加载（父级 dynamic import）；像素比钳制 1.75；页面隐藏暂停；
   prefers-reduced-motion：停自转/巡航，光点按真实起讫进度静置。 */
import { ref, onMounted, onUnmounted, watch } from 'vue';
import * as THREE from 'three';
import { AIRPORTS } from '../nav.js';

const props = defineProps({
  variant: { type: String, default: 'board' },   // board | login
  arcs: { type: Array, default: () => [] },       // {key, from, to, state, hero, progress, dur, phase}
  showLabels: { type: Boolean, default: true },
});
const emit = defineEmits(['ready', 'fallback', 'anchors']);

const CENTER_LON = 112; // 镜头正对的中心经度（华东/华中之间），枢纽经度带 104–121 全部落在正面半球
const R = 1;
const reduceMotion = typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

const rootRef = ref(null);
const labelEls = new Map();
function setLabel(code, el) { if (el) labelEls.set(code, el); else labelEls.delete(code); }
/* 标签排版（制图去冲突）：真实地理下 HGH–SHA、CAN–SZX 在球面上仅距数像素，逐个标注必然叠字——
   按地图制图惯例合并为集群标签（两行、锚在两点中点）；其余枢纽单行侧置（代号+站名一行，按经度分左右） */
const SINGLE_LABELS = AIRPORTS.filter((a) => !['HGH', 'SHA', 'CAN', 'SZX'].includes(a.code));
const LABEL_DIR = { PEK: 'r', XIY: 'l', CTU: 'l' };
const CLUSTERS = [
  { id: 'CL_HS', codes: ['HGH', 'SHA'], dir: 'r' },
  { id: 'CL_CS', codes: ['CAN', 'SZX'], dir: 'l' },
].map((c) => ({ ...c, rows: c.codes.map((code) => AIRPORTS.find((a) => a.code === code)) }));

function latLon(lat, lon, r) {
  const phi = ((90 - lat) * Math.PI) / 180;
  const theta = ((lon - CENTER_LON) * Math.PI) / 180;
  return new THREE.Vector3(r * Math.sin(phi) * Math.sin(theta), r * Math.cos(phi), r * Math.sin(phi) * Math.cos(theta));
}

/* 统一蓝色阶（与 styles.css 令牌同源，禁止再引入色阶外的蓝） */
const B = { 200: 0xbcd8ff, 300: 0x8fbfff, 400: 0x5ea2ff, 500: 0x4789e8, 600: 0x2f6fd6 };
const AMBER = 0xffb648;

let renderer, scene, camera, group, raf = null, clock = null;
let cometRunners = [];   // {curve, dur, phase, sprites:[core,t1,t2], progress}
let pulseRings = [];     // {sprite, phase}
let disposed = false;
let par = { x: 0, y: 0 }, parT = { x: 0, y: 0 };
let lastAnchorKey = '';

function radialTexture(inner = 'rgba(255,255,255,1)', mid = 'rgba(160,200,255,.45)') {
  const c = document.createElement('canvas'); c.width = c.height = 128;
  const g = c.getContext('2d');
  const grad = g.createRadialGradient(64, 64, 0, 64, 64, 64);
  grad.addColorStop(0, inner); grad.addColorStop(0.35, mid); grad.addColorStop(1, 'rgba(120,170,255,0)');
  g.fillStyle = grad; g.fillRect(0, 0, 128, 128);
  const tex = new THREE.CanvasTexture(c); tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
}
function ringTexture() {
  const c = document.createElement('canvas'); c.width = c.height = 128;
  const g = c.getContext('2d');
  g.strokeStyle = 'rgba(190,220,255,.95)'; g.lineWidth = 7;
  g.beginPath(); g.arc(64, 64, 52, 0, Math.PI * 2); g.stroke();
  g.strokeStyle = 'rgba(140,190,255,.35)'; g.lineWidth = 16;
  g.beginPath(); g.arc(64, 64, 48, 0, Math.PI * 2); g.stroke();
  const tex = new THREE.CanvasTexture(c); tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
}

function buildGlobe() {
  group = new THREE.Group();
  scene.add(group);

  /* 球体本体：Fresnel 边缘辉光着色（深空蓝心 → 航空蓝边缘），不透明以遮挡背面点阵 */
  const body = new THREE.Mesh(
    new THREE.SphereGeometry(R, 64, 64),
    new THREE.ShaderMaterial({
      uniforms: { cDeep: { value: new THREE.Color(0x0a1626) }, cRim: { value: new THREE.Color(B[400]) } },
      vertexShader: `varying vec3 vN; varying vec3 vV;
        void main(){ vec4 mv = modelViewMatrix * vec4(position, 1.0); vN = normalize(normalMatrix * normal); vV = normalize(-mv.xyz); gl_Position = projectionMatrix * mv; }`,
      fragmentShader: `uniform vec3 cDeep; uniform vec3 cRim; varying vec3 vN; varying vec3 vV;
        void main(){ float f = pow(1.0 - abs(dot(normalize(vN), normalize(vV))), 2.6); gl_FragColor = vec4(cDeep + cRim * f * 0.5, 1.0); }`,
    })
  );
  group.add(body);

  /* 大气层：背侧放大球 + 经典辉光着色 */
  const atmo = new THREE.Mesh(
    new THREE.SphereGeometry(R * 1.18, 64, 64),
    new THREE.ShaderMaterial({
      uniforms: { cRim: { value: new THREE.Color(B[400]) } },
      vertexShader: `varying vec3 vN; void main(){ vN = normalize(normalMatrix * normal); gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }`,
      fragmentShader: `uniform vec3 cRim; varying vec3 vN;
        void main(){ float i = pow(0.72 - dot(normalize(vN), vec3(0.0, 0.0, 1.0)), 3.5); gl_FragColor = vec4(cRim, 1.0) * i * 0.55; }`,
      side: THREE.BackSide, blending: THREE.AdditiveBlending, transparent: true, depthWrite: false,
    })
  );
  group.add(atmo);

  /* 点阵球面（Fibonacci 均匀布点） */
  const N = 2400, pos = new Float32Array(N * 3), ga = Math.PI * (3 - Math.sqrt(5));
  for (let i = 0; i < N; i++) {
    const y = 1 - (i / (N - 1)) * 2, rad = Math.sqrt(Math.max(0, 1 - y * y)), th = ga * i;
    pos[i * 3] = Math.cos(th) * rad * R * 1.003; pos[i * 3 + 1] = y * R * 1.003; pos[i * 3 + 2] = Math.sin(th) * rad * R * 1.003;
  }
  const dotGeo = new THREE.BufferGeometry();
  dotGeo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  group.add(new THREE.Points(dotGeo, new THREE.PointsMaterial({
    color: 0x7fb2f0, size: 0.0115, sizeAttenuation: true, transparent: true, opacity: 0.5, depthWrite: false,
  })));

  /* 经纬网格（刻度感：让“地图”落到真实物象上） */
  const grat = [];
  const seg = 96;
  for (let lat = -60; lat <= 75; lat += 15) {
    for (let i = 0; i < seg; i++) {
      grat.push(latLon(lat, (i / seg) * 360 - 180, R * 1.001), latLon(lat, ((i + 1) / seg) * 360 - 180, R * 1.001));
    }
  }
  for (let lon = -180; lon < 180; lon += 15) {
    for (let i = 0; i < seg; i++) {
      grat.push(latLon(-90 + (i / seg) * 180, lon, R * 1.001), latLon(-90 + ((i + 1) / seg) * 180, lon, R * 1.001));
    }
  }
  const gratGeo = new THREE.BufferGeometry().setFromPoints(grat);
  group.add(new THREE.LineSegments(gratGeo, new THREE.LineBasicMaterial({ color: B[400], transparent: true, opacity: 0.055, depthWrite: false })));

  /* 枢纽标记：同种画法，仅主数据 hub 标记略大（与 v8 的 2D 节点规格一致，非钦定主角） */
  const dotTex = radialTexture();
  for (const a of AIRPORTS) {
    const p = latLon(a.lat, a.lon, R);
    const m = new THREE.Mesh(
      new THREE.SphereGeometry(a.hub ? 0.0145 : 0.0105, 12, 12),
      new THREE.MeshBasicMaterial({ color: a.hub ? B[400] : 0xdceaff })
    );
    m.position.copy(p);
    group.add(m);
    const glow = new THREE.Sprite(new THREE.SpriteMaterial({ map: dotTex, color: B[400], transparent: true, opacity: a.hub ? 0.42 : 0.24, blending: THREE.AdditiveBlending, depthWrite: false }));
    glow.position.copy(p); glow.scale.setScalar(a.hub ? 0.07 : 0.046);
    group.add(glow);
  }
}

let arcGroup = null;
function buildArcs() {
  if (arcGroup) {
    group.remove(arcGroup);
    arcGroup.traverse((o) => { o.geometry?.dispose?.(); if (o.material) { (Array.isArray(o.material) ? o.material : [o.material]).forEach((m) => { m.map?.dispose?.(); m.dispose?.(); }); } });
  }
  arcGroup = new THREE.Group();
  group.add(arcGroup);
  cometRunners = []; pulseRings = [];
  const byCode = Object.fromEntries(AIRPORTS.map((a) => [a.code, a]));
  const dotTex = radialTexture();
  const ringTex = ringTexture();
  const pulsed = new Set();

  for (const arc of props.arcs) {
    const A = byCode[arc.from], Bv = byCode[arc.to];
    if (!A || !Bv) continue;
    const a = latLon(A.lat, A.lon, R), b = latLon(Bv.lat, Bv.lon, R);
    const chord = a.distanceTo(b);
    const hero = !!arc.hero;
    const lift = (0.085 + chord * 0.5) * (arc.state === 'enroute' ? (hero ? 1.4 : 1.18) : 1);
    const ctrl = a.clone().add(b).multiplyScalar(0.5).normalize().multiplyScalar(R + lift);
    const curve = new THREE.QuadraticBezierCurve3(a, ctrl, b);

    if (arc.state === 'enroute') {
      /* 在途 = 主角：实体管线（亮）+ 主角再加辉光管 */
      const tube = new THREE.Mesh(
        new THREE.TubeGeometry(curve, 56, hero ? 0.0068 : 0.005, 6, false),
        new THREE.MeshBasicMaterial({ color: hero ? B[300] : B[400], transparent: true, opacity: hero ? 0.98 : 0.85 })
      );
      arcGroup.add(tube);
      if (hero) {
        const glowTube = new THREE.Mesh(
          new THREE.TubeGeometry(curve, 56, 0.016, 6, false),
          new THREE.MeshBasicMaterial({ color: B[400], transparent: true, opacity: 0.13, blending: THREE.AdditiveBlending, depthWrite: false })
        );
        arcGroup.add(glowTube);
      }
      /* 巡航光点：头 + 两级拖尾（与 v8 同语义，rAF 相位滞后采样）；
         第一主角的光点头用信号琥珀——全屏琥珀 ≤3 处的其中一处 */
      const mk = (scale, color, opacity, tex = dotTex) => {
        const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, color, transparent: true, opacity, blending: THREE.AdditiveBlending, depthWrite: false }));
        s.scale.setScalar(scale); arcGroup.add(s); return s;
      };
      /* 第一主角的光点头用暖色渐变贴图（不用白色贴图染色——加色混合下会被冲白，琥珀必须自带色） */
      const heroTex = hero ? radialTexture('rgba(255,198,96,1)', 'rgba(255,150,50,.45)') : dotTex;
      const sprites = [
        mk(hero ? 0.105 : 0.085, hero ? AMBER : 0xeaf4ff, 1, heroTex),
        mk(0.055, B[300], 0.5),
        mk(0.038, B[300], 0.3),
      ];
      cometRunners.push({ curve, dur: arc.dur ?? 6, phase: arc.phase ?? 0, progress: arc.progress ?? null, sprites });
      /* 在途起讫节点：呼吸脉冲环（语义与 2D 版图例一致） */
      for (const code of [arc.from, arc.to]) {
        if (pulsed.has(code)) continue;
        pulsed.add(code);
        const hub = byCode[code];
        const ring = new THREE.Sprite(new THREE.SpriteMaterial({ map: ringTex, color: B[300], transparent: true, opacity: 0.7, depthWrite: false }));
        ring.position.copy(latLon(hub.lat, hub.lon, R * 1.01));
        arcGroup.add(ring);
        pulseRings.push({ sprite: ring, phase: pulsed.size * 0.37 });
      }
    } else {
      /* 非在途：细线。待起飞虚线（中亮）、已到达残影（暗）、骨干网（极暗虚线）、登录装饰（中暗） */
      const conf = {
        scheduled: { color: B[500], opacity: 0.5, dashed: true },
        arrived: { color: B[600], opacity: 0.22, dashed: false },
        backbone: { color: B[300], opacity: 0.13, dashed: true },
        decor: { color: B[400], opacity: 0.3, dashed: false },
      }[arc.state] ?? { color: B[500], opacity: 0.4, dashed: false };
      const geo = new THREE.BufferGeometry().setFromPoints(curve.getPoints(56));
      const mat = new THREE.LineDashedMaterial({ color: conf.color, transparent: true, opacity: conf.opacity, dashSize: 0.03, gapSize: 0.022, depthWrite: false });
      if (!conf.dashed) { mat.dashSize = 9999; mat.gapSize = 0; }
      const line = new THREE.Line(geo, mat);
      line.computeLineDistances();
      arcGroup.add(line);
      /* 待起飞：始发节点静态空心点（与巡航光点形态区分，同 v8 语义） */
      if (arc.state === 'scheduled') {
        const ring = new THREE.Sprite(new THREE.SpriteMaterial({ map: ringTex, color: 0x94a3b4, transparent: true, opacity: 0.8, depthWrite: false }));
        ring.position.copy(a.clone().multiplyScalar(1.01));
        ring.scale.setScalar(0.045);
        arcGroup.add(ring);
      }
    }
  }
}

const tmpV = new THREE.Vector3();
function updateLabelsAndAnchors() {
  const el = rootRef.value;
  if (!el || !camera || !group) return;
  const w = el.clientWidth, h = el.clientHeight;
  if (!w || !h) return;
  const anchors = {};
  const px = {};   // 各枢纽的像素坐标与可见性（标签与锚点共用同一份投影）
  let key = '';
  for (const a of AIRPORTS) {
    tmpV.copy(latLon(a.lat, a.lon, R));
    group.localToWorld(tmpV);
    const world = tmpV.clone();
    tmpV.project(camera);
    const x = (tmpV.x * 0.5 + 0.5) * w, y = (0.5 - tmpV.y * 0.5) * h;
    const facing = world.sub(group.position).normalize().dot(camera.position.clone().sub(world).normalize());
    const vis = facing > 0.12 && tmpV.z < 1;
    px[a.code] = { x, y, vis };
    anchors[a.code] = { x: (x / w) * 100, y: (y / h) * 100, vis };
    key += `${a.code}:${anchors[a.code].x.toFixed(1)},${anchors[a.code].y.toFixed(1)};`;
  }
  if (props.showLabels) {
    const place = (id, x, y, vis) => {
      const lab = labelEls.get(id);
      if (!lab) return;
      lab.style.transform = `translate(${x.toFixed(1)}px, ${y.toFixed(1)}px)`;
      lab.style.opacity = vis ? '1' : '0';
    };
    for (const a of SINGLE_LABELS) place(a.code, px[a.code].x, px[a.code].y, px[a.code].vis);
    for (const c of CLUSTERS) {
      const p1 = px[c.codes[0]], p2 = px[c.codes[1]];
      place(c.id, (p1.x + p2.x) / 2, (p1.y + p2.y) / 2, p1.vis && p2.vis);
    }
  }
  if (key !== lastAnchorKey) { lastAnchorKey = key; emit('anchors', anchors); window.__globeAnchors = anchors; /* 验收钩子：供截图脚本核对投影锚点 */ }
}

let parHandler = null;
function animate() {
  raf = requestAnimationFrame(animate);
  const t = clock.getElapsedTime();
  /* 镜头：限定角摆动（枢纽经度带窄，±10° 内全员不脱位）+ 固定俯角微晃 */
  if (reduceMotion) {
    group.rotation.y = 0.04; group.rotation.x = 0.13;
  } else if (props.variant === 'login') {
    group.rotation.y = Math.sin(t * 0.03) * 0.22;
    group.rotation.x = 0.13 + Math.sin(t * 0.023) * 0.02;
  } else {
    group.rotation.y = Math.sin(t * 0.05) * 0.17;
    group.rotation.x = 0.13 + Math.sin(t * 0.041) * 0.025;
  }
  /* 指针视差：与全站同一条 0.06 阻尼 lerp */
  par.x += (parT.x - par.x) * 0.06;
  par.y += (parT.y - par.y) * 0.06;
  camera.position.set(par.x * 0.22, 0.78 - par.y * 0.14, 3.62);
  camera.lookAt(0, 0.02, 0); /* 视点固定在世界原点：球心偏移由 group.position 决定（否则偏移会被 lookAt 抵消） */

  for (const r of cometRunners) {
    let p;
    if (reduceMotion) p = r.progress ?? 0.5;
    else p = (t / r.dur + r.phase) % 1;
    const set = (s, tt) => { if (s) s.position.copy(r.curve.getPoint(((tt % 1) + 1) % 1)); };
    set(r.sprites[0], p); set(r.sprites[1], p - 0.028); set(r.sprites[2], p - 0.056);
  }
  if (!reduceMotion) {
    for (const pr of pulseRings) {
      const s = (t * 0.42 + pr.phase) % 1;
      pr.sprite.scale.setScalar(0.05 + s * 0.1);
      pr.sprite.material.opacity = (1 - s) * 0.75;
    }
  } else {
    for (const pr of pulseRings) { pr.sprite.scale.setScalar(0.075); pr.sprite.material.opacity = 0.45; }
  }
  renderer.render(scene, camera);
  updateLabelsAndAnchors();
}

function onVis() {
  if (document.hidden) { if (raf) cancelAnimationFrame(raf); raf = null; }
  else if (!raf && renderer) { clock.getElapsedTime(); raf = requestAnimationFrame(animate); }
}

let ro = null;
onMounted(() => {
  const el = rootRef.value;
  try {
    if (!window.WebGLRenderingContext) throw new Error('no webgl');
    renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'high-performance' });
    if (!renderer.getContext()) throw new Error('no ctx');
  } catch (e) {
    emit('fallback');
    return;
  }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.75));
  renderer.setSize(el.clientWidth, el.clientHeight);
  el.appendChild(renderer.domElement);

  scene = new THREE.Scene();
  camera = new THREE.PerspectiveCamera(36, el.clientWidth / Math.max(1, el.clientHeight), 0.1, 50);
  buildGlobe();
  if (props.variant === 'login') { group.position.set(1.02, 0.02, 0); group.scale.setScalar(1.05); }
  else { group.position.set(0.60, -0.04, 0); group.scale.setScalar(1.12); }
  buildArcs();

  clock = new THREE.Clock();
  raf = requestAnimationFrame(animate);
  /* 首帧渲染完成后通知父级切入（父级在此之前保留 2D 图，不留白） */
  requestAnimationFrame(() => requestAnimationFrame(() => { if (!disposed) emit('ready'); }));

  ro = new ResizeObserver(() => {
    if (!renderer || !el.clientWidth) return;
    renderer.setSize(el.clientWidth, el.clientHeight);
    camera.aspect = el.clientWidth / Math.max(1, el.clientHeight);
    camera.updateProjectionMatrix();
  });
  ro.observe(el);
  document.addEventListener('visibilitychange', onVis);
  parHandler = (e) => {
    const r = el.getBoundingClientRect();
    if (!r.width) return;
    parT.x = ((e.clientX - r.left) / r.width - 0.5) * 2;
    parT.y = ((e.clientY - r.top) / r.height - 0.5) * 2;
  };
  if (!reduceMotion) window.addEventListener('pointermove', parHandler, { passive: true });
});

watch(() => props.arcs, () => { if (group) buildArcs(); });

onUnmounted(() => {
  disposed = true;
  if (raf) cancelAnimationFrame(raf);
  ro?.disconnect();
  document.removeEventListener('visibilitychange', onVis);
  if (parHandler) window.removeEventListener('pointermove', parHandler);
  if (renderer) {
    scene?.traverse((o) => { o.geometry?.dispose?.(); if (o.material) { (Array.isArray(o.material) ? o.material : [o.material]).forEach((m) => { m.map?.dispose?.(); m.dispose?.(); }); } });
    renderer.dispose();
    renderer.domElement?.remove();
  }
});
</script>

<template>
  <div ref="rootRef" class="globe3d" :class="`gv-${variant}`" aria-hidden="true">
    <div v-if="showLabels" class="g-labels">
      <div v-for="a in SINGLE_LABELS" :key="a.code" class="g-lab" :class="`dir-${LABEL_DIR[a.code]}`" :ref="(el) => setLabel(a.code, el)">
        <div class="g-lines"><span class="g-line"><b>{{ a.code }}</b><i>{{ a.name }}</i></span></div>
      </div>
      <div v-for="c in CLUSTERS" :key="c.id" class="g-lab" :class="`dir-${c.dir}`" :ref="(el) => setLabel(c.id, el)">
        <div class="g-lines">
          <span v-for="r in c.rows" :key="r.code" class="g-line"><b>{{ r.code }}</b><i>{{ r.name }}</i></span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.globe3d { position: absolute; inset: 0; overflow: hidden; pointer-events: none; }
.globe3d canvas { display: block; }
.gv-login { opacity: .6; }
.g-labels { position: absolute; inset: 0; }
.g-lab {
  position: absolute; left: 0; top: 0; width: 0; height: 0;
  transition: opacity .3s ease;
  will-change: transform;
}
/* 单行侧置标签：代号（等宽粗）+ 站名一行，整体从节点侧方伸出，不再上下堆叠 */
.g-lines { position: absolute; top: 0; display: flex; flex-direction: column; gap: 4px; }
.dir-r .g-lines { left: 15px; transform: translateY(-50%); }
.dir-l .g-lines { right: 15px; transform: translateY(-50%); align-items: flex-end; }
.g-line {
  display: flex; gap: 7px; align-items: baseline; white-space: nowrap;
  text-shadow: 0 1px 6px rgba(7, 9, 13, .95), 0 0 3px rgba(7, 9, 13, .9);
}
.g-line b { font-family: var(--mono); font-size: 13.5px; font-weight: 600; letter-spacing: .08em; color: var(--tx-1); }
.g-line i { font-style: normal; font-size: 11.5px; color: var(--tx-2); }
@media (max-width: 980px) { .g-labels { display: none; } }
</style>
