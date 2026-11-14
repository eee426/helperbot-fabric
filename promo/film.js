'use strict';
// HelperBot 宣传片：全部画面由 renderFrame(秒) 画出，同一个时间永远得到同一帧。
// 预览：直接用浏览器打开 index.html。出片：node render.mjs。

const W = 1920, H = 1080, DUR = 78;
const cv = document.getElementById('c');
const ctx = cv.getContext('2d');

const C = {
  bg: '#0d1117', panel: '#161c26', panel2: '#1d2531', line: '#2a3444',
  text: '#e8edf4', dim: '#8a97a8', green: '#5be37d', amber: '#f2b84b',
  red: '#ff6b6b', pink: '#f5a9c8', blue: '#4aa3ff',
};
const SANS = '"Microsoft YaHei UI","Microsoft YaHei","PingFang SC","Noto Sans SC",sans-serif';
const MONO = '"Cascadia Mono",Consolas,"Courier New",' + SANS;

// ---------- 小工具 ----------
const clamp = (x, a = 0, b = 1) => Math.max(a, Math.min(b, x));
const lerp = (a, b, k) => a + (b - a) * k;
const p = (t, a, b) => clamp((t - a) / (b - a));
const eo = x => 1 - Math.pow(1 - x, 3);
const eio = x => x < .5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
const back = x => { const c = 1.70158; return 1 + (c + 1) * Math.pow(x - 1, 3) + c * Math.pow(x - 1, 2); };
const hash = (a, b) => { const s = Math.sin(a * 127.1 + b * 311.7) * 43758.5453; return s - Math.floor(s); };

let GA = 1; // 当前分镜的整体透明度
function A(a) { ctx.globalAlpha = clamp(GA * a); }

function text(s, x, y, o = {}) {
  ctx.font = `${o.weight || 500} ${o.size || 40}px ${o.mono ? MONO : SANS}`;
  ctx.textAlign = o.align || 'left';
  ctx.textBaseline = o.base || 'middle';
  A(o.alpha ?? 1);
  ctx.fillStyle = o.color || C.text;
  ctx.fillText(s, x, y);
}
function textW(s, o = {}) {
  ctx.font = `${o.weight || 500} ${o.size || 40}px ${o.mono ? MONO : SANS}`;
  return ctx.measureText(s).width;
}
function rise(s, x, y, k, o = {}) {
  if (k <= 0) return;
  const e = eo(k);
  text(s, x, y + (1 - e) * 26, { ...o, alpha: (o.alpha ?? 1) * e });
}
function box(x, y, w, h, color, alpha = 1, r = 12) {
  A(alpha); ctx.fillStyle = color;
  ctx.beginPath(); ctx.roundRect(x, y, w, h, r); ctx.fill();
}
function frame(x, y, w, h, color, alpha = 1, lw = 2, r = 12, dash = null) {
  A(alpha); ctx.strokeStyle = color; ctx.lineWidth = lw;
  ctx.setLineDash(dash || []);
  ctx.beginPath(); ctx.roundRect(x, y, w, h, r); ctx.stroke();
  ctx.setLineDash([]);
}
function seg(x1, y1, x2, y2, color, alpha = 1, lw = 3, dash = null) {
  A(alpha); ctx.strokeStyle = color; ctx.lineWidth = lw; ctx.lineCap = 'round';
  ctx.setLineDash(dash || []);
  ctx.beginPath(); ctx.moveTo(x1, y1); ctx.lineTo(x2, y2); ctx.stroke();
  ctx.setLineDash([]);
}
function dot(x, y, r, color, alpha = 1) {
  A(alpha); ctx.fillStyle = color;
  ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fill();
}
function chip(s, x, y, k, color = C.green, o = {}) {
  const size = o.size || 30;
  const w = textW(s, { size, mono: o.mono }) + 44;
  if (k > 0) {
    const e = eo(k), yy = y + (1 - e) * 20;
    box(x, yy - 28, w, 56, C.panel2, e, 28);
    frame(x, yy - 28, w, 56, color, e * .7, 2, 28);
    text(s, x + 22, yy + 1, { size, color, alpha: e, mono: o.mono });
  }
  return w;
}
function head(small, big, t) {
  rise(small, 160, 104, p(t, .1, .6), { size: 30, color: C.green, weight: 700 });
  rise(big, 160, 176, p(t, .25, .85), { size: 58, weight: 800 });
}

// ---------- 像素方块和机器人 ----------
const TILE = {
  sand: [212, 198, 150], stone: [112, 118, 126], grass: [72, 122, 66], water: [50, 96, 172], plank: [178, 136, 80],
  lava: [226, 98, 34], dirt: [122, 88, 60], cherry: [236, 160, 196], brick: [138, 142, 150],
  glass: [150, 204, 226], void: [15, 19, 27],
};
function tile(x, y, s, kind, seed = 0, alpha = 1, n = 4, amp = .09) {
  const base = TILE[kind], q = s / n;
  A(alpha);
  for (let i = 0; i < n; i++) for (let j = 0; j < n; j++) {
    const k = 1 + (hash(seed * 1.31 + i * 7.13, j * 3.77 + seed) - .5) * 2 * amp;
    ctx.fillStyle = `rgb(${base[0] * k | 0},${base[1] * k | 0},${base[2] * k | 0})`;
    ctx.fillRect(x + i * q, y + j * q, q + .6, q + .6);
  }
}
function sprite(rows, pal, x, y, s, alpha = 1) {
  A(alpha);
  for (let r = 0; r < rows.length; r++) for (let c = 0; c < rows[r].length; c++) {
    const ch = rows[r][c];
    if (ch === '.') continue;
    ctx.fillStyle = pal[ch];
    ctx.fillRect(x + c * s, y + r * s, s + .6, s + .6);
  }
}
const BOT = [
  '.....tt.....',
  '.....tt.....',
  '..hhhhhhhh..',
  '.hHHHHHHHHh.',
  '.hHvvvvvvHh.',
  '.hHvevvevHh.',
  '.hHvvvvvvHh.',
  '.hHHHHHHHHh.',
  '..hhhhhhhh..',
  '...bbbbbb...',
  '.abBBBBBBba.',
  '.abBBccBBba.',
  '.abBBBBBBba.',
  '...bbbbbb...',
  '...ll..ll...',
  '...ll..ll...',
];
const BOT_PAL = { t: '#f2b84b', h: '#3a4656', H: '#c9d3df', v: '#141c24', e: '#5be37d', b: '#2f3947', B: '#8fa0b3', c: '#f2b84b', a: '#6c7a8a', l: '#3a4656' };
// 以 (cx, cy) 为中心画机器人；s 是每个像素的大小
function robot(cx, cy, s, alpha, T) {
  const pal = (T % 3.2) < .12 ? { ...BOT_PAL, e: BOT_PAL.v } : BOT_PAL;
  sprite(BOT, pal, cx - 6 * s, cy - 8 * s, s, alpha);
}
const PICK = ['........', '..hhhh..', '.h..shh.', '...s..h.', '..s...h.', '.s......', 's.......', '........'];
const PICK_PAL = { h: '#b9c8d8', s: '#a57a4a' };

function along(pts, f) {
  let total = 0; const L = [];
  for (let i = 1; i < pts.length; i++) { const d = Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]); L.push(d); total += d; }
  let d = clamp(f) * total;
  for (let i = 0; i < L.length; i++) { if (d <= L[i] || i === L.length - 1) { const k = L[i] ? d / L[i] : 0; return [lerp(pts[i][0], pts[i + 1][0], k), lerp(pts[i][1], pts[i + 1][1], k)]; } d -= L[i]; }
  return pts[0];
}

// ---------- 背景 ----------
function bg(T) {
  ctx.globalAlpha = 1;
  ctx.fillStyle = C.bg; ctx.fillRect(0, 0, W, H);
  ctx.fillStyle = '#18202b';
  const off = (T * 6) % 60;
  for (let x = 30; x < W; x += 60) for (let y = 30 - 60 + off; y < H; y += 60) ctx.fillRect(x - 1.5, y - 1.5, 3, 3);
  const g = ctx.createRadialGradient(W / 2, H / 2, 300, W / 2, H / 2, 1200);
  g.addColorStop(0, 'rgba(0,0,0,0)'); g.addColorStop(1, 'rgba(0,0,0,.55)');
  ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
}

// ---------- 1. 痛点（0–6 秒）----------
function s1(t) {
  rise('生存后期 · 大工程', 160, 130, p(t, .15, .7), { size: 34, color: C.dim });
  const cols = 18, rows = 10, s = 46, ox = 160, oy = 210;
  const total = cols * (rows - 2);
  const filled = Math.floor(eo(p(t, .4, 3.0)) * total);
  for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) {
    const x = ox + c * s, y = oy + r * s;
    if (r < 2) { // 屋顶：缺的樱花树叶
      const blink = .35 + .3 * Math.sin(t * 6 + c * .4);
      frame(x + 3, y + 3, s - 6, s - 6, C.pink, p(t, 2.8, 3.3) * blink, 2, 4, [6, 5]);
      continue;
    }
    const order = (rows - 1 - r) * cols + c;
    if (order >= filled) { frame(x + 3, y + 3, s - 6, s - 6, C.line, .5, 1, 4); continue; }
    const glass = r >= 4 && r <= 7 && c % 4 >= 1 && c % 4 <= 2 && c > 0 && c < cols - 1;
    tile(x, y, s - 2, glass ? 'glass' : 'brick', c * 13 + r, 1);
  }
  // 进度条
  const pr = eo(p(t, .4, 3.0)) * .8;
  const shake = t > 3.1 && t < 3.6 ? Math.sin(t * 70) * 5 * (1 - p(t, 3.1, 3.6)) : 0;
  box(ox + shake, 700, cols * s, 22, C.panel2, 1, 11);
  box(ox + shake, 700, cols * s * pr, 22, t > 3.05 ? C.amber : C.green, 1, 11);
  text(`进度 ${Math.round(pr * 100)}%`, ox, 752, { size: 30, color: t > 3.05 ? C.amber : C.dim, mono: true });
  rise('卡住了', ox + 190, 752, p(t, 3.1, 3.5), { size: 30, color: C.amber, weight: 700 });
  // 材料清单
  const lx = 1120;
  box(lx, 210, 640, 380, C.panel, eo(p(t, .5, 1)), 16);
  rise('材料清单', lx + 40, 262, p(t, .6, 1.1), { size: 30, color: C.dim });
  const rowsL = [['石砖', '1,728 / 1,728', C.green, 1.2], ['玻璃', '512 / 512', C.green, 1.9], ['樱花树叶', '0 / 640', C.red, 2.7]];
  rowsL.forEach(([name, n, col, at], i) => {
    const y = 340 + i * 84, k = p(t, at, at + .4);
    const a = col === C.red ? .65 + .35 * Math.sin(t * 7) : 1;
    rise(name, lx + 40, y, k, { size: 40, weight: 700, alpha: a });
    rise(n, lx + 600, y, k, { size: 36, color: col, align: 'right', mono: true, alpha: a });
  });
  rise('要用，量不小，', 160, 880, p(t, 3.5, 4.1), { size: 76, weight: 800 });
  rise('又不值得造机器。', 160 + textW('要用，量不小，', { size: 76, weight: 800 }), 880, p(t, 4.1, 4.7), { size: 76, weight: 800, color: C.amber });
}

// ---------- 2. 登场（6–12 秒）----------
function s2(t, T) {
  // 聊天框
  const cmd = '/bot get cherry_leaves 640';
  const n = Math.floor(p(t, .4, 2.0) * cmd.length);
  box(160, 820, 1000, 84, '#080b10', eo(p(t, 0, .4)) * .92, 10);
  frame(160, 820, 1000, 84, C.line, eo(p(t, 0, .4)), 2, 10);
  const cur = (t % .8) < .45 && t < 4.2 ? '_' : '';
  text('> ' + cmd.slice(0, n) + cur, 190, 863, { size: 40, mono: true, alpha: p(t, .1, .4) });
  rise('[HelperBot] 计划好了，等你确认。', 190, 760, p(t, 4.3, 4.8), { size: 34, mono: true, color: C.green });
  // 机器人落下
  const k = p(t, 2.3, 3.1);
  if (k > 0) {
    const y = lerp(-260, 470, back(k)) + (k >= 1 ? Math.sin(T * 2.2) * 8 : 0);
    A(.35 * eo(k)); ctx.fillStyle = '#000'; ctx.beginPath(); ctx.ellipse(1500, 740, 190 * eo(k), 26 * eo(k), 0, 0, Math.PI * 2); ctx.fill();
    robot(1500, y, 30, 1, T);
    const d = p(t, 2.85, 3.5);
    if (d > 0 && d < 1) for (let i = 0; i < 10; i++) {
      const a = Math.PI + (i / 9) * Math.PI;
      dot(1500 + Math.cos(a) * (120 + d * 160), 750 + Math.sin(a) * d * 40, 9 * (1 - d), C.dim, (1 - d) * .8);
    }
  }
  rise('HelperBot', 150, 330, p(t, 2.9, 3.5), { size: 170, weight: 800 });
  rise('采购机器人', 160, 480, p(t, 3.3, 3.9), { size: 68, weight: 700, color: C.green });
  let x = 160;
  x += chip('Fabric 1.21.11', x, 600, p(t, 3.8, 4.2), C.dim) + 18;
  x += chip('纯服务端', x, 600, p(t, 4.0, 4.4), C.dim) + 18;
  chip('原版客户端直接进', x, 600, p(t, 4.2, 4.6), C.dim);
}

// ---------- 3. 三条原则（12–22 秒）----------
function card(cx, k) {
  const e = eo(k), y = 250 + (1 - e) * 40;
  box(cx - 260, y, 520, 640, C.panel, e, 18);
  frame(cx - 260, y, 520, 640, C.line, e, 2, 18);
  return e;
}
function s3(t, T) {
  rise('核心原则', 160, 130, p(t, .1, .6), { size: 30, color: C.green, weight: 700 });
  // 不作弊
  let cx = 360, k = p(t, .3, .9);
  if (k > 0) {
    const e = card(cx, k), lt = Math.max(0, t - .9);
    rise('不作弊', cx, 340, k, { size: 76, weight: 800, align: 'center' });
    const cyc = 1.5, f = (lt % cyc) / cyc, breaks = Math.floor(lt / cyc);
    const sc = f > .9 ? 1 - (f - .9) / .1 : Math.min(1, f / .08), bs = 170 * sc;
    tile(cx - bs / 2, 540 - bs / 2, bs, 'stone', breaks, e);
    A(e); ctx.fillStyle = '#20252c';
    const cracks = Math.floor(f / .9 * 14);
    for (let i = 0; i < cracks && sc >= 1; i++) ctx.fillRect(cx - 85 + Math.floor(hash(i, breaks) * 10) * 17, 455 + Math.floor(hash(i + 9, breaks) * 10) * 17, 17, 17);
    sprite(PICK, PICK_PAL, cx + 60 + Math.sin(lt * 14) * 6, 430 + Math.abs(Math.sin(lt * 14)) * 14, 12, e);
    const ratio = Math.max(.1, 1 - .07 * (breaks + (f > .9 ? 1 : 0)));
    text('耐久', cx - 190, 690, { size: 28, color: C.dim, alpha: e });
    box(cx - 120, 678, 310, 24, C.panel2, e, 12);
    box(cx - 120, 678, 310 * ratio, 24, C.green, e, 12);
    if (f < .5 && breaks > 0) text('−1', cx + 150, 650 - f * 50, { size: 30, color: C.amber, alpha: e * (1 - f * 2), mono: true, weight: 700 });
    rise('耗时、耐久，全照原版算', cx, 800, p(t, .7, 1.3), { size: 32, color: C.dim, align: 'center' });
  }
  // 不越界
  cx = 960; k = p(t, 3.3, 3.9);
  if (k > 0) {
    const e = card(cx, k), lt = t - 3.9;
    rise('不越界', cx, 340, k, { size: 76, weight: 800, align: 'center' });
    text('你放进去的', cx - 186, 424, { size: 26, color: C.dim, alpha: e });
    const items = [['pick'], ['plank', '64'], ['stone', '32'], ['plank', '64']];
    for (let r = 0; r < 3; r++) for (let c = 0; c < 6; c++) {
      const x = cx - 186 + c * 62, y = 450 + r * 62, i = r * 6 + c;
      box(x, y, 56, 56, '#0c1016', e, 6); frame(x, y, 56, 56, C.line, e, 2, 6);
      if (i < items.length) {
        const ik = back(p(lt, .2 + i * .35, .5 + i * .35));
        if (ik > 0) {
          if (items[i][0] === 'pick') sprite(PICK, PICK_PAL, x + 28 - 20 * ik, y + 28 - 20 * ik, 5 * ik, e);
          else { tile(x + 28 - 18 * ik, y + 28 - 18 * ik, 36 * ik, items[i][0], i, e); text(items[i][1], x + 52, y + 44, { size: 18, align: 'right', mono: true, weight: 700, alpha: e * ik }); }
        }
      }
    }
    // 箱子：不开
    const bx = cx - 40, by = 666;
    tile(bx, by, 80, 'plank', 5, e); A(e); ctx.fillStyle = '#5a4126'; ctx.fillRect(bx, by + 26, 80, 10); ctx.fillStyle = '#d8dde4'; ctx.fillRect(bx + 33, by + 22, 14, 20);
    const xk = eo(p(lt, 2.0, 2.5));
    if (xk > 0) { seg(bx - 14, by - 14, bx - 14 + 108 * xk, by - 14 + 108 * xk, C.red, e, 9); seg(bx + 94, by - 14, bx + 94 - 108 * xk, by - 14 + 108 * xk, C.red, e, 9); }
    rise('只用你给的，不开任何容器', cx, 800, p(t, 3.7, 4.3), { size: 32, color: C.dim, align: 'center' });
  }
  // 不犯傻
  cx = 1560; k = p(t, 6.3, 6.9);
  if (k > 0) {
    const e = card(cx, k), lt = Math.max(0, t - 6.9);
    rise('不犯傻', cx, 340, k, { size: 76, weight: 800, align: 'center' });
    const s = 60, ox = cx - 210, oy = 420;
    for (let r = 0; r < 5; r++) for (let c = 0; c < 7; c++) tile(ox + c * s, oy + r * s, s - 2, 'grass', c * 5 + r * 11, e);
    const px = ox + 3 * s, py = oy + 2 * s;
    box(px + 8, py + 8, s - 18, s - 18, C.amber, e, 4);
    frame(px - 6, py - 6, s + 10, s + 10, C.red, e * (.4 + .4 * Math.sin(T * 6)), 3, 8);
    const pts = [[0, 2], [1, 2], [1, 1], [5, 1], [5, 2], [6, 2]].map(([c, r]) => [ox + c * s + s / 2 - 1, oy + r * s + s / 2 - 1]);
    A(e * .9); ctx.strokeStyle = C.text; ctx.lineWidth = 4; ctx.setLineDash([10, 10]); ctx.lineJoin = 'round';
    ctx.beginPath(); pts.forEach(([x, y], i) => i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)); ctx.stroke(); ctx.setLineDash([]);
    const [rx, ry] = along(pts, (lt % 3) / 2.6);
    robot(rx, ry, 3.4, e, T);
    rise('压力板、红石、岩浆：绕开', cx, 800, p(t, 6.7, 7.3), { size: 32, color: C.dim, align: 'center' });
  }
}

// ---------- 4. 规划内核（22–30 秒）----------
function flow(x1, y1, x2, y2, k, T, color = C.line) {
  if (k <= 0) return;
  const e = eo(k);
  seg(x1, y1, lerp(x1, x2, e), lerp(y1, y2, e), color, .9, 3);
  if (k >= 1) for (let i = 0; i < 2; i++) { const f = (T * .6 + i * .5) % 1; dot(lerp(x1, x2, f), lerp(y1, y2, f), 5, C.green, Math.sin(f * Math.PI)); }
}
function s4(t, T) {
  head('规划内核', '知识来自游戏数据，不靠猜', t);
  const src = ['方块战利品表', '合成与熔炼配方', '群系地物配置', '结构模板'];
  const hx = 650, hy = 520;
  src.forEach((s, i) => {
    const y = 310 + i * 140, k = p(t, .4 + i * .18, .9 + i * .18);
    flow(470, y, hx - 78, hy + (i - 1.5) * 26, p(t, 1.1 + i * .12, 1.9 + i * .12), T + i * .2);
    if (k > 0) { const e = eo(k); box(140 - (1 - e) * 40, y - 42, 330, 84, C.panel, e, 12); frame(140 - (1 - e) * 40, y - 42, 330, 84, C.line, e, 2, 12); text(s, 305 - (1 - e) * 40, y + 1, { size: 32, align: 'center', alpha: e }); }
  });
  const hk = p(t, 1.8, 2.3);
  if (hk > 0) { dot(hx, hy, 78 * back(hk), C.panel2); A(1); ctx.strokeStyle = C.green; ctx.lineWidth = 3; ctx.beginPath(); ctx.arc(hx, hy, 78 * back(hk), 0, Math.PI * 2); ctx.stroke(); text('获取', hx, hy - 20, { size: 32, align: 'center', weight: 700, alpha: hk }); text('知识', hx, hy + 22, { size: 32, align: 'center', weight: 700, alpha: hk }); }
  flow(hx + 78, hy, 850, hy, p(t, 2.4, 2.9), T);
  const tk = p(t, 2.7, 3.2);
  if (tk > 0) { const e = eo(tk); box(850, hy - 80, 290, 160, C.panel, e, 14); frame(850, hy - 80, 290, 160, C.pink, e, 3, 14); tile(880, hy - 32, 64, 'cherry', 3, e); text('樱花树叶', 962, hy - 20, { size: 34, weight: 700, alpha: e }); text('× 640', 962, hy + 26, { size: 34, color: C.pink, mono: true, alpha: e }); }
  const routes = [
    ['去樱花树林，用剪刀剪', '18,400 刻 ≈ 15 分钟', true, 5.5],
    ['用精准采集的工具挖', '背包里没有', false, 4.7],
    ['合成', '没有这个配方', false, 5.1],
  ];
  routes.forEach(([a, b, ok, vt], i) => {
    const y = 350 + i * 170, k = p(t, 3.6 + i * .2, 4.1 + i * .2), v = p(t, vt, vt + .35);
    flow(1140, hy, 1240, y, p(t, 3.2 + i * .15, 3.8 + i * .15), T + i * .3, v > 0 && ok ? C.green : C.line);
    if (k <= 0) return;
    const e = eo(k), dimA = ok ? 1 : lerp(1, .45, v), x = 1240 + (1 - e) * 40;
    box(x, y - 62, 540, 124, C.panel, e * dimA, 14);
    frame(x, y - 62, 540, 124, ok && v > 0 ? C.green : C.line, e * dimA, ok && v > 0 ? 4 : 2, 14);
    text(a, x + 30, y - 20, { size: 32, weight: 700, alpha: e * dimA });
    if (v > 0) text((ok ? '' : '✕ ') + b, x + 30, y + 26, { size: 27, color: ok ? C.green : C.red, alpha: e * v, mono: ok });
    if (ok && v > 0) { const w = 104; box(x + 540 - w - 16, y - 46, w, 44, C.green, eo(v), 22); text('最省', x + 540 - w / 2 - 16, y - 23, { size: 26, color: '#0d1117', weight: 800, align: 'center', alpha: eo(v) }); }
  });
  rise('计划先发给你，确认后才执行', 160, 950, p(t, 6.2, 6.7), { size: 40, weight: 700 });
  chip('/bot confirm', 160 + textW('计划先发给你，确认后才执行', { size: 40, weight: 700 }) + 30, 950, p(t, 6.5, 6.9), C.green, { mono: true });
}

// ---------- 5. 寻路内核（30–38 秒）----------
const GRID = (() => {
  const cols = 30, rows = 12, cells = [];
  for (let r = 0; r < rows; r++) {
    let row = '';
    for (let c = 0; c < cols; c++) {
      let ch = '.';
      if (c === 7 || (c === 8 && r !== 5)) ch = '#';
      if (c >= 13 && c <= 15) ch = '~';
      if (c === 20 || c === 21) ch = ' ';
      if (c >= 24 && c <= 25 && r >= 3 && r <= 8) ch = 'L';
      row += ch;
    }
    cells.push(row);
  }
  const cost = { '.': 1, '#': 6, '~': 2.5, ' ': 4 };
  const start = [2, 6], goal = [28, 5], key = (c, r) => r * cols + c;
  const g = new Map([[key(...start), 0]]), from = new Map(), closed = new Set(), order = [];
  const open = [[...start]];
  const hF = (c, r) => Math.abs(c - goal[0]) + Math.abs(r - goal[1]);
  while (open.length) {
    let bi = 0, bf = Infinity;
    open.forEach(([c, r], i) => { const f = g.get(key(c, r)) + hF(c, r) * 1.001; if (f < bf) { bf = f; bi = i; } });
    const [c, r] = open.splice(bi, 1)[0];
    if (closed.has(key(c, r))) continue;
    closed.add(key(c, r)); order.push([c, r]);
    if (c === goal[0] && r === goal[1]) break;
    for (const [dc, dr] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
      const nc = c + dc, nr = r + dr;
      if (nc < 0 || nr < 0 || nc >= cols || nr >= rows) continue;
      const ch = cells[nr][nc];
      if (ch === 'L') continue;
      let w = cost[ch];
      for (const [ec, er] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) if ((cells[nr + er] || '')[nc + ec] === 'L') w += 3; // 离岩浆远一点
      const ng = g.get(key(c, r)) + w;
      if (ng < (g.get(key(nc, nr)) ?? Infinity)) { g.set(key(nc, nr), ng); from.set(key(nc, nr), [c, r]); open.push([nc, nr]); }
    }
  }
  const path = []; let cur = goal;
  while (cur) { path.unshift(cur); cur = from.get(key(...cur)); }
  const cum = [0];
  for (let i = 1; i < path.length; i++) cum.push(cum[i - 1] + cost[cells[path[i][1]][path[i][0]]]);
  return { cols, rows, cells, order, path, cum, start, goal };
})();
const MOVE = { '.': ['走', C.text], '#': ['挖穿', C.amber], '~': ['游', C.blue], ' ': ['搭桥', C.green] };
function s5(t, T) {
  head('寻路内核 · 陆地', '像真人一样，一刻一刻地走', t);
  const G = GRID, s = 52, ox = 180, oy = 250;
  const u = p(t, 4.3, 7.5) * G.cum[G.cum.length - 1];
  let idx = 0; while (idx < G.path.length - 1 && G.cum[idx + 1] <= u) idx++;
  const fr = idx < G.path.length - 1 ? (u - G.cum[idx]) / (G.cum[idx + 1] - G.cum[idx]) : 0;
  const onPath = new Map(G.path.map(([c, r], i) => [r * G.cols + c, i]));
  const a0 = eo(p(t, .2, .8));
  for (let r = 0; r < G.rows; r++) for (let c = 0; c < G.cols; c++) {
    const ch = G.cells[r][c], x = ox + c * s, y = oy + r * s, pi = onPath.get(r * G.cols + c);
    if (ch === '.') tile(x, y, s - 2, 'grass', c * 3 + r * 17, a0);
    else if (ch === '#') {
      if (pi !== undefined && pi <= idx) tile(x, y, s - 2, 'dirt', c + r, a0);
      else { tile(x, y, s - 2, 'stone', c * 3 + r * 17, a0); if (pi === idx + 1) { A(a0); ctx.fillStyle = '#20252c'; for (let i = 0; i < fr * 8; i++) ctx.fillRect(x + Math.floor(hash(i, 3) * 5) * 10, y + Math.floor(hash(i, 8) * 5) * 10, 10, 10); } }
    } else if (ch === '~') { tile(x, y, s - 2, 'water', c * 3 + r * 17, a0, 4, .06); A(a0 * .25); ctx.fillStyle = '#bfe0ff'; ctx.fillRect(x + ((T * 14 + r * 13) % (s - 14)), y + 12 + (c % 2) * 18, 12, 3); }
    else if (ch === ' ') { if (pi !== undefined && pi <= idx + 1) tile(x, y, s - 2, 'plank', c + r, a0 * (pi === idx + 1 ? clamp(fr * 3) : 1)); else tile(x, y, s - 2, 'void', c + r, a0, 4, .2); }
    else if (ch === 'L') tile(x, y, s - 2, 'lava', c * 3 + r * 17 + Math.floor(T * 3), a0, 4, .14);
  }
  // A* 展开
  const shown = Math.floor(p(t, .7, 3.3) * G.order.length), fadeX = 1 - p(t, 3.9, 4.6) * .75;
  for (let i = 0; i < shown; i++) { const [c, r] = G.order[i]; box(ox + c * s + 8, oy + r * s + 8, s - 18, s - 18, C.blue, (i > shown - 12 ? .9 : .38) * fadeX, 4); }
  // 路线
  const cxy = ([c, r]) => [ox + c * s + s / 2 - 1, oy + r * s + s / 2 - 1];
  const pk = p(t, 3.3, 4.2);
  if (pk > 0) {
    const nSeg = pk * (G.path.length - 1);
    A(.95); ctx.strokeStyle = C.green; ctx.lineWidth = 7; ctx.lineJoin = 'round'; ctx.lineCap = 'round'; ctx.beginPath();
    for (let i = 0; i <= Math.floor(nSeg) && i < G.path.length; i++) { const [x, y] = cxy(G.path[i]); i ? ctx.lineTo(x, y) : ctx.moveTo(x, y); }
    const i0 = Math.floor(nSeg); if (i0 < G.path.length - 1) { const [x1, y1] = cxy(G.path[i0]), [x2, y2] = cxy(G.path[i0 + 1]), f = nSeg - i0; ctx.lineTo(lerp(x1, x2, f), lerp(y1, y2, f)); }
    ctx.stroke();
  }
  // 目标和机器人
  const [gx, gy] = cxy(G.goal);
  tile(gx - 20, gy - 20, 40, 'cherry', 2, a0); frame(gx - 27, gy - 27, 54, 54, C.pink, a0 * (.5 + .5 * Math.sin(T * 5)), 3, 6);
  const [x1, y1] = cxy(G.path[idx]), [x2, y2] = cxy(G.path[Math.min(idx + 1, G.path.length - 1)]);
  const rx = lerp(x1, x2, fr), ry = lerp(y1, y2, fr);
  robot(rx, ry - 2, 3.6, a0, T);
  if (t > 4.3 && idx < G.path.length - 1) {
    const [nc, nr] = G.path[idx + 1], [name, col] = MOVE[G.cells[nr][nc]];
    const w = textW(name, { size: 28, weight: 700 }) + 32;
    box(rx - w / 2, ry - 86, w, 46, '#080b10', .92, 23); frame(rx - w / 2, ry - 86, w, 46, col, 1, 2, 23);
    text(name, rx, ry - 62, { size: 28, weight: 700, color: col, align: 'center' });
  }
  text(`已用 ${String(Math.floor(u * 4.6)).padStart(3, ' ')} 游戏刻`, 1740, 176, { size: 34, mono: true, align: 'right', color: C.dim, alpha: p(t, 4.1, 4.5) });
  // 图例
  let x = 180;
  [['走', 'grass'], ['挖穿，按原版挖掘时间', 'stone'], ['游', 'water'], ['搭桥，用你给的方块', 'plank'], ['岩浆：不去', 'lava']].forEach(([n, k], i) => {
    const kk = p(t, 1 + i * .2, 1.5 + i * .2); if (kk <= 0) return;
    tile(x, 916, 36, k, i, eo(kk)); text(n, x + 50, 935, { size: 28, color: C.dim, alpha: eo(kk) });
    x += 50 + textW(n, { size: 28 }) + 46;
  });
}

// ---------- 5b. 寻路内核 · 水下 ----------
// 速度和气量取自 path/Air.java：横游 0.12 格/刻，上下 0.08 格/刻，一口气 300 刻，出水每刻回 4 点。
const SEA = (() => {
  const cols = 36, rows = 13, surf = 3;
  const floorOf = c => {
    if (c < 5) return 3;
    if (c <= 7) return [5, 7, 9][c - 5];
    if (c >= 15 && c <= 18) return [7, 5, 5, 8][c - 15];
    if (c >= 32) return [9, 7, 5, 4][c - 32];
    return hash(c, 5) > .7 ? 9 : 10;
  };
  const isWater = (c, r) => c >= 5 && c < cols && r >= surf && r < floorOf(c);
  const key = (c, r) => r * cols + c;
  function bfs(a) {
    const prev = new Map([[key(...a), null]]), order = [a];
    for (let i = 0; i < order.length; i++) {
      const [c, r] = order[i];
      for (const [dc, dr] of [[1, 0], [0, 1], [-1, 0], [0, -1]]) {
        const n = [c + dc, r + dr];
        if (!isWater(...n) || prev.has(key(...n))) continue;
        prev.set(key(...n), [c, r]); order.push(n);
      }
    }
    return { prev, order };
  }
  const kelp = [11, 24].map((c, i) => ({ c, r: floorOf(c) - 1, h: 4 + i, got: 0 }));
  const flood = bfs([5, surf]).order;
  kelp.forEach(k => { k.found = flood.findIndex(([c, r]) => c === k.c && r === k.r); });
  const tl = [{ c: 4, r: 2, tick: 0, air: 300, kind: '走' }, { c: 5, r: surf, tick: 5, air: 300, kind: '下水' }];
  let tick = 5, air = 300;
  function leg(to, kind) {
    const from = tl[tl.length - 1], { prev } = bfs([from.c, from.r]), path = [];
    for (let cur = to; cur; cur = prev.get(key(...cur))) path.unshift(cur);
    for (let i = 1; i < path.length; i++) {
      const [c, r] = path[i], dt = r !== path[i - 1][1] ? 1 / .08 : 1 / .12;
      tick += dt; air = r === surf ? Math.min(300, air + 4 * dt) : air - dt;
      tl.push({ c, r, tick, air, kind });
    }
  }
  function breathe() { const l = tl[tl.length - 1]; tick += (300 - air) / 4; air = 300; tl.push({ c: l.c, r: l.r, tick, air, kind: '换气' }); }
  kelp.forEach(k => {
    leg([k.c, surf], '游'); leg([k.c, k.r], '下潜'); k.got = tick; leg([k.c, surf], '上浮'); breathe();
  });
  return { cols, rows, surf, floorOf, kelp, flood, tl, total: tick };
})();
function s5b(t, T) {
  head('寻路内核 · 水下', '下水之前，先把整片海想清楚', t);
  const S = SEA, s = 44, ox = 168, oy = 250, a0 = eo(p(t, .2, .8));
  const cxy = (c, r) => [ox + c * s + s / 2, oy + r * s + s / 2];
  box(ox, oy, S.cols * s, S.surf * s, '#111b2b', a0, 0);
  for (let c = 0; c < S.cols; c++) for (let r = S.surf; r < S.rows; r++) {
    const x = ox + c * s, y = oy + r * s, f = S.floorOf(c);
    if (r >= f) tile(x, y, s, c < 5 ? (r === f ? 'grass' : 'dirt') : (c >= 15 && c <= 18 ? 'stone' : 'sand'), c * 3 + r * 17, a0);
    else { A(a0); ctx.fillStyle = `rgb(${46 - r * 2},${96 - r * 4},${176 - r * 7})`; ctx.fillRect(x, y, s + .6, s + .6); }
  }
  A(a0 * .5); ctx.fillStyle = '#bfe0ff';
  for (let c = 5; c < S.cols; c++) ctx.fillRect(ox + c * s, oy + S.surf * s + Math.sin(T * 2 + c * .9) * 2, s, 3);
  // 时间：机器人按真实刻数走
  const tt = p(t, 4.0, 10.3) * S.total;
  let i = 0; while (i < S.tl.length - 2 && S.tl[i + 1].tick <= tt) i++;
  const a = S.tl[i], b = S.tl[i + 1], fr = clamp((tt - a.tick) / (b.tick - a.tick));
  const air = t < 4 ? 300 : lerp(a.air, b.air, fr);
  // 海带
  S.kelp.forEach(k => {
    if (t >= 4 && tt >= k.got) return;
    A(a0); ctx.fillStyle = '#3f9a4d';
    for (let j = 0; j < k.h; j++) ctx.fillRect(ox + k.c * s + s / 2 - 6 + Math.sin(T * 2 + j * .8) * 4, oy + (k.r - j) * s + 2, 12, s - 2);
  });
  // 洪水搜索
  const shown = Math.floor(p(t, .7, 3.0) * S.flood.length), fade = 1 - p(t, 3.5, 4.1);
  if (fade > 0) for (let j = 0; j < shown; j++) { const [c, r] = S.flood[j]; box(ox + c * s + 7, oy + r * s + 7, s - 14, s - 14, '#7fe7ff', (j > shown - 14 ? .85 : .3) * fade, 4); }
  S.kelp.forEach(k => {
    if (shown <= k.found || fade <= 0) return;
    const [x, y] = cxy(k.c, k.r - k.h);
    frame(ox + k.c * s - 4, oy + (k.r - k.h + 1) * s - 4, s + 8, k.h * s + 8, C.green, fade * (.6 + .4 * Math.sin(T * 6)), 3, 8);
    text('能到', x, y - 4, { size: 26, weight: 700, color: C.green, align: 'center', alpha: fade });
  });
  // 路线
  const pk = p(t, 3.1, 3.9);
  if (pk > 0) {
    const n = pk * (S.tl.length - 1);
    A(.9); ctx.strokeStyle = C.green; ctx.lineWidth = 5; ctx.lineJoin = 'round'; ctx.lineCap = 'round'; ctx.setLineDash([2, 12]); ctx.beginPath();
    for (let j = 0; j <= Math.floor(n) && j < S.tl.length; j++) { const [x, y] = cxy(S.tl[j].c, S.tl[j].r); j ? ctx.lineTo(x, y) : ctx.moveTo(x, y); }
    ctx.stroke(); ctx.setLineDash([]);
  }
  // 机器人
  const [x1, y1] = cxy(a.c, a.r), [x2, y2] = cxy(b.c, b.r);
  const rx = t < 4 ? x1 : lerp(x1, x2, fr), ry = (t < 4 ? y1 : lerp(y1, y2, fr)) - 4;
  robot(rx, ry, 3.2, a0, T);
  if (t > 4 && t < 10.3) {
    const col = b.kind === '换气' ? C.green : b.kind === '上浮' ? C.amber : '#7fe7ff', w = textW(b.kind, { size: 26, weight: 700 }) + 30;
    box(rx - w / 2, ry - 80, w, 42, '#080b10', .92, 21); frame(rx - w / 2, ry - 80, w, 42, col, 1, 2, 21);
    text(b.kind, rx, ry - 58, { size: 26, weight: 700, color: col, align: 'center' });
  }
  // 气
  const ak = eo(p(t, 3.4, 3.9)), acol = air < 120 ? C.amber : C.green;
  text(`气 ${String(Math.round(air)).padStart(3, ' ')} / 300 刻`, 1752, 132, { size: 32, mono: true, align: 'right', color: acol, alpha: ak });
  box(1340, 160, 412, 18, C.panel2, ak, 9); box(1340, 160, 412 * air / 300, 18, acol, ak, 9);
  let x = 168;
  x += chip('洪水搜索：一次查完所有目标，不挖不放', x, 884, p(t, .9, 1.4), '#7fe7ff', { size: 26 }) + 16;
  x += chip('留不够游回水面的气，这条路就不走', x, 884, p(t, 4.6, 5.1), C.amber, { size: 26 }) + 16;
  chip('换气写在路线里', x, 884, p(t, 6.4, 6.9), C.green, { size: 26 });
  rise('实测：约 20 万格的一片海，游戏内 3.6 秒搜完', 168, 958, p(t, 2.0, 2.6), { size: 30, color: C.dim });
}

// ---------- 5c. 寻路内核 · 过闸 ----------
function s5c(t, T) {
  head('寻路内核 · 每一步都过闸', '能不能到，只有它说了算', t);
  const nodes = [
    [160, 'A* 搜路', '走 · 游 · 挖 · 搭桥 · 往上垫 · 往下挖', C.blue],
    [590, '气够游上去吗？', '不够：这条路不要', C.amber],
    [1020, '要挖时，头在水下？', '是：这条路不要', C.amber],
    [1450, '有完整路线', '才出发；没有就不出发', C.green],
  ];
  const y0 = 290, w = 340, h = 150, ym = y0 + h / 2, k0 = p(t, .5, 1.1);
  seg(160, ym, 160 + (1630 * eo(k0)), ym, C.line, 1, 3);
  // 一条条候选路线流过去，不合格的掉下去
  if (t > 1.2) for (let i = 0; i < 46; i++) {
    const f = (T * .22 + i / 46) % 1, fate = hash(i, 2), x = lerp(330, 1790, f);
    const gate = fate < .38 ? 760 : fate < .64 ? 1190 : 0;
    if (gate && x > gate) { const d = (x - gate) / 150; if (d < 1) dot(gate + d * 26, ym + h / 2 + 8 + d * d * 120, 8, C.red, (1 - d) * p(t, 1.2, 1.6)); }
    else dot(x, ym, 8, gate ? C.dim : C.green, p(t, 1.2, 1.6) * Math.sin(f * Math.PI) ** .3);
  }
  nodes.forEach(([x, a, b, col], i) => {
    const k = p(t, .4 + i * .22, .9 + i * .22); if (k <= 0) return;
    const e = eo(k), y = y0 + (1 - e) * 30;
    box(x, y, w, h, C.panel, e, 16); frame(x, y, w, h, col, e, 3, 16);
    text(a, x + w / 2, y + 56, { size: 34, weight: 800, align: 'center', alpha: e });
    text(b, x + w / 2, y + 108, { size: i ? 24 : 19, color: i ? col : C.dim, align: 'center', alpha: e });
  });
  const tiles = [
    [v => String(Math.round(v * 67)), '次提交', '改在寻路和移动的代码上'],
    [v => String(Math.round(v * 68)), '项水下回归测试', '修一个，记一个'],
    [v => Math.round(v * 100) + '万', '格', '一次全量搜索的上限，在后台线程里算'],
  ];
  tiles.forEach(([num, label, sub], i) => {
    const k = p(t, 2.2 + i * .3, 2.7 + i * .3); if (k <= 0) return;
    const e = eo(k), x = 160 + i * 550, y = 620 + (1 - e) * 30;
    box(x, y, 530, 230, C.panel, e, 18); frame(x, y, 530, 230, C.line, e, 2, 18);
    const n = num(eo(p(t, 2.3 + i * .3, 3.8 + i * .3)));
    text(n, x + 40, y + 84, { size: 92, weight: 800, color: C.green, alpha: e, mono: true });
    text(label, x + 56 + textW(n, { size: 92, weight: 800, mono: true }), y + 98, { size: 34, weight: 700, alpha: e });
    text(sub, x + 40, y + 178, { size: 26, color: C.dim, alpha: e });
  });
  rise('代价是真实的游戏刻，不是调出来的系数', 160, 930, p(t, 4.2, 4.8), { size: 34, color: C.dim });
}

// ---------- 6. 反射内核 ----------
function s6(t, T) {
  head('反射内核', '先活下来，再干活', t);
  const rowsR = [['换气', '水下的气只够游上去'], ['自卫', '有怪在打它、盯上它'], ['吃东西', '吃了不浪费才吃'], ['补方块', '搭路的方块不够了'], ['任务', '以上都没事']];
  let active = -1, ev = null;
  if (t > 1.5) active = 4;
  if (t > 2.6 && t < 3.7) { active = 0; ev = ['在水下，气快用完', C.blue, 2.6]; }
  if (t > 4.3 && t < 5.2) { active = 1; ev = ['一只僵尸盯上了它', C.red, 4.3]; }
  rowsR.forEach(([name, cond], i) => {
    const y = 280 + i * 108, k = p(t, .3 + i * .18, .8 + i * .18); if (k <= 0) return;
    const e = eo(k), on = i === active, x = 200 - (1 - e) * 50;
    box(x, y, 980, 88, on ? '#1f3326' : C.panel, e, 14);
    frame(x, y, 980, 88, on ? C.green : C.line, e, on ? 4 : 2, 14);
    dot(x + 56, y + 44, 24, on ? C.green : C.panel2, e);
    text(String(i + 1), x + 56, y + 46, { size: 28, weight: 800, align: 'center', color: on ? '#0d1117' : C.dim, alpha: e, mono: true });
    text(name, x + 110, y + 45, { size: 40, weight: 700, alpha: e, color: on ? C.green : C.text });
    text(cond, x + 950, y + 45, { size: 28, color: C.dim, align: 'right', alpha: e });
    if (on) text('我在忙', x + 330, y + 46, { size: 26, color: C.green, alpha: e * (.6 + .4 * Math.sin(T * 8)), weight: 700 });
  });
  // 右侧：正在发生什么
  box(1260, 280, 500, 520, C.panel, eo(p(t, 1.2, 1.7)), 16);
  rise('这一刻', 1300, 334, p(t, 1.3, 1.8), { size: 28, color: C.dim });
  if (ev) {
    const k = p(t, ev[2], ev[2] + .25);
    robot(1510, 520, 14, eo(k), T);
    if (ev[1] === C.blue) for (let i = 0; i < 6; i++) { const f = (T * .7 + i / 6) % 1; dot(1400 + i * 44, 690 - f * 330, 10 + (i % 3) * 4, C.blue, .5 * (1 - f) * k); }
    else { const pulse = .5 + .5 * Math.sin(T * 10); frame(1380, 400, 260, 250, C.red, k * pulse, 5, 20); }
    rise(ev[0], 1510, 740, k, { size: 34, weight: 700, color: ev[1], align: 'center' });
  } else if (t > 1.5) {
    robot(1510, 520 + Math.sin(T * 9) * 4, 14, eo(p(t, 1.5, 1.9)), T);
    rise('在干活', 1510, 740, p(t, 1.5, 1.9), { size: 34, weight: 700, color: C.green, align: 'center' });
  }
  rise('每一刻从上往下问：谁说“我在忙”，下面的就都不执行', 200, 900, p(t, 1.9, 2.5), { size: 34, color: C.dim });
}

// ---------- 7. 保护与诚实（44–50 秒）----------
function s7(t, T) {
  const second = p(t, 3.5, 3.9);
  head('保护体系', '你的东西，它不碰', t);
  const s = 50, ox = 210, oy = 260, cs = 300, a0 = eo(p(t, .2, .7)) * (1 - second * .82);
  for (let r = 0; r < 12; r++) for (let c = 0; c < 30; c++) tile(ox + c * s, oy + r * s, s - 1, 'grass', c * 7 + r * 29, a0, 4, .07);
  for (let c = 0; c <= 5; c++) seg(ox + c * cs, oy, ox + c * cs, oy + 600, '#0d1117', a0 * .5, 2, [8, 8]);
  seg(ox, oy + cs, ox + 1500, oy + cs, '#0d1117', a0 * .5, 2, [8, 8]);
  // 受保护的区块和里面的房子
  const px = ox + 2 * cs, py = oy;
  for (let r = 0; r < 3; r++) for (let c = 0; c < 4; c++) tile(px + 50 + c * 50, py + 20 + r * 44, 49, r === 0 ? 'brick' : 'plank', c + r * 9, a0);
  const pk = p(t, .8, 1.3);
  box(px, py, cs, cs, C.amber, a0 * pk * .13, 0);
  frame(px, py, cs, cs, C.amber, a0 * pk, 4, 0);
  if (pk > 0) for (let i = 0; i < 16; i++) { const f = ((i / 16) + T * .08) % 1, per = f * 4, side = Math.floor(per), k = per - side; const x = side === 0 ? px + k * cs : side === 1 ? px + cs : side === 2 ? px + cs - k * cs : px; const y = side === 0 ? py : side === 1 ? py + k * cs : side === 2 ? py + cs : py + cs - k * cs; dot(x, y, 5, C.amber, a0 * pk); }
  chip('/bot protect', px + 16, py - 40, p(t, .9, 1.3) * (1 - second), C.amber, { mono: true, size: 24 });
  // 机器人横穿
  const rx = lerp(ox + 60, ox + 1440, p(t, 1.0, 3.5)), ry = oy + 236;
  const inside = rx > px && rx < px + cs;
  robot(rx, ry + Math.sin(T * 10) * 3, 4.6, a0 * (inside ? .38 : 1), T);
  if (inside && second < 1) {
    box(rx - 250, ry + 70, 500, 56, '#080b10', .92 * (1 - second), 28);
    text('灵魂状态：不挖 · 不放 · 不捡 · 不碰', rx, ry + 99, { size: 26, align: 'center', color: C.amber, alpha: 1 - second });
  }
  rise('保护的区块，从基岩到天空，它只路过', 210, 930, p(t, 1.3, 1.9) * (1 - second), { size: 34, color: C.dim });
  // 诚实失败
  if (second > 0) {
    rise('做不到，就说实话', 960, 470, p(t, 3.7, 4.2), { size: 96, weight: 800, align: 'center' });
    const k = eo(p(t, 4.2, 4.6));
    box(360, 600, 1200, 96, '#080b10', k * .95, 12); frame(360, 600, 1200, 96, C.red, k * .8, 2, 12);
    const msg = '[HelperBot] 做不到：缺少精准采集工具。请给我一把。';
    text(msg.slice(0, Math.floor(p(t, 4.3, 5.2) * msg.length)), 400, 649, { size: 36, mono: true, color: C.red, alpha: k });
  }
}

// ---------- 8. 数字（50–55 秒）----------
function s8(t) {
  head('现在的样子', '一块稳的地基', t);
  const tiles = [
    [v => String(Math.round(v * 151)), '个回归场景', '全部通过'],
    [v => (v * 2.2).toFixed(1) + '万', '行确定性逻辑', '不接大模型，可解释'],
    [v => String(Math.round(v * 3)), '个内核', '寻路 · 规划 · 反射'],
    [() => '0', '个客户端模组', '原版客户端直接进'],
  ];
  tiles.forEach(([num, label, sub], i) => {
    const k = p(t, .4 + i * .25, .9 + i * .25); if (k <= 0) return;
    const e = eo(k), x = 140 + i * 420, y = 320 + (1 - e) * 40;
    box(x, y, 380, 420, C.panel, e, 18); frame(x, y, 380, 420, C.line, e, 2, 18);
    text(num(eo(p(t, .5 + i * .25, 1.9 + i * .25))), x + 190, y + 140, { size: 104, weight: 800, color: C.green, align: 'center', alpha: e, mono: true });
    text(label, x + 190, y + 270, { size: 36, weight: 700, align: 'center', alpha: e });
    text(sub, x + 190, y + 334, { size: 26, color: C.dim, align: 'center', alpha: e });
  });
  let x = 140;
  x += chip('Fabric 1.21.11', x, 880, p(t, 2.0, 2.4), C.dim) + 18;
  x += chip('纯服务端', x, 880, p(t, 2.15, 2.55), C.dim) + 18;
  chip('兼容 Carpet', x, 880, p(t, 2.3, 2.7), C.dim);
}

// ---------- 9. 前方的路（55–60 秒）----------
function s9(t, T) {
  rise('前方的路', 160, 130, p(t, .1, .5), { size: 30, color: C.green, weight: 700 });
  const layers = [['内核', 1], ['动作', 1], ['任务', 1], ['接口', 0], ['大模型', 0], ['家园能力', 0], ['外观', 0], ['记忆和陪伴', 0]];
  layers.forEach(([name, have], i) => {
    const k = p(t, .2 + i * .14, .6 + i * .14); if (k <= 0) return;
    const e = back(k), y = 870 - i * 82 - (1 - e) * 60, top = i === 7, glow = top ? p(t, 1.9, 2.4) : 0;
    if (have) box(160, y, 700, 66, '#1f3326', k, 10);
    else if (glow > 0) box(160, y, 700, 66, C.amber, glow * (.16 + .06 * Math.sin(T * 5)), 10);
    frame(160, y, 700, 66, have ? C.green : glow > 0 ? C.amber : C.line, k, have || glow > 0 ? 3 : 2, 10, have ? null : [10, 8]);
    text(String(i + 1), 196, y + 35, { size: 26, mono: true, color: C.dim, alpha: k });
    text(name, 240, y + 35, { size: 34, weight: 700, alpha: k, color: have ? C.green : glow > 0 ? C.amber : C.text });
    text(have ? (i === 1 ? '部分已有' : '已有') : '未来', 830, y + 35, { size: 24, color: C.dim, align: 'right', alpha: k });
  });
  rise('从工业机器人，', 980, 380, p(t, 1.7, 2.2), { size: 74, weight: 800 });
  rise('到记得你的电子玩家。', 980, 480, p(t, 2.2, 2.8), { size: 74, weight: 800, color: C.amber });
  const k = p(t, 3.0, 3.6);
  if (k > 0) {
    robot(1050, 720 + Math.sin(T * 2.2) * 5, 11 * back(k), 1, T);
    rise('HelperBot', 1150, 700, k, { size: 92, weight: 800 });
    rise('一层一层，往上盖', 1154, 780, p(t, 3.3, 3.9), { size: 32, color: C.dim });
  }
}

// ---------- 时间轴 ----------
const SCENES = [[0, 6, s1], [6, 12, s2], [12, 22, s3], [22, 30, s4], [30, 38, s5], [38, 49, s5b], [49, 56, s5c], [56, 62, s6], [62, 68, s7], [68, 73, s8], [73, 78, s9]];
function renderFrame(T) {
  bg(T);
  for (const [a, b, f] of SCENES) {
    if (T < a || T >= b) continue;
    const lt = T - a, d = b - a, out = b === DUR ? .6 : .25;
    GA = Math.min(p(lt, 0, .3), 1 - p(lt, d - out, d));
    f(lt, T);
  }
  GA = 1; ctx.globalAlpha = 1;
}
window.renderFrame = renderFrame;
window.FILM = { W, H, DUR };

// ---------- 预览 ----------
const q = new URLSearchParams(location.search);
if (q.has('render')) {
  document.body.className = 'render';
  renderFrame(0);
} else {
  document.body.className = 'preview';
  const scrub = document.getElementById('scrub'), btn = document.getElementById('play'), time = document.getElementById('time');
  scrub.max = DUR;
  let T = parseFloat(q.get('t') || '0'), playing = true, last = performance.now();
  btn.onclick = () => { playing = !playing; btn.textContent = playing ? '暂停' : '播放'; };
  scrub.oninput = () => { T = parseFloat(scrub.value); };
  addEventListener('keydown', e => { if (e.code === 'Space') { e.preventDefault(); btn.click(); } });
  (function loop(now) {
    if (playing) { T += (now - last) / 1000; if (T >= DUR) T = 0; scrub.value = T; }
    last = now;
    renderFrame(T);
    time.textContent = T.toFixed(2) + ' / ' + DUR;
    requestAnimationFrame(loop);
  })(performance.now());
}
