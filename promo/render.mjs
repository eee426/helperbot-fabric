// 把 index.html 逐帧渲染成 MP4。
//   node render.mjs                 → out/helperbot-promo.mp4（1920×1080，60 帧）
//   node render.mjs --stills 3,9,15 [目录]  → 只截这几秒的画面，用来检查
// 依赖（全局安装，别的视频项目也能用）：npm i -g puppeteer-core；winget install Gyan.FFmpeg
import { createRequire } from 'node:module';
import { spawn, execSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const globalModules = execSync('npm root -g', { encoding: 'utf8' }).trim();
const puppeteer = createRequire(path.join(globalModules, 'x.js'))('puppeteer-core');

const FPS = 60;
const browserPath = [
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
].find(existsSync);
if (!browserPath) throw new Error('没找到 Edge 或 Chrome');

function findFfmpeg() {
  try { execSync('ffmpeg -version', { stdio: 'ignore' }); return 'ffmpeg'; } catch { /* 这个终端的 PATH 还没刷新 */ }
  const winget = path.join(process.env.LOCALAPPDATA || '', 'Microsoft/WinGet');
  const link = path.join(winget, 'Links/ffmpeg.exe');
  if (existsSync(link)) return link;
  const pkgs = path.join(winget, 'Packages');
  if (existsSync(pkgs)) for (const pkg of readdirSync(pkgs).filter(n => n.startsWith('Gyan.FFmpeg'))) {
    for (const ver of readdirSync(path.join(pkgs, pkg))) {
      const exe = path.join(pkgs, pkg, ver, 'bin/ffmpeg.exe');
      if (existsSync(exe)) return exe;
    }
  }
  throw new Error('没找到 ffmpeg：winget install Gyan.FFmpeg');
}

const browser = await puppeteer.launch({ executablePath: browserPath, headless: true, args: ['--force-device-scale-factor=1', '--hide-scrollbars'] });
const page = await browser.newPage();
await page.setViewport({ width: 1920, height: 1080, deviceScaleFactor: 1 });
page.on('pageerror', e => { console.error('页面出错：', e.message); process.exitCode = 1; });
await page.goto(pathToFileURL(path.join(here, 'index.html')).href + '?render');
await page.evaluate(() => document.fonts.ready);
const DUR = await page.evaluate(() => window.FILM.DUR);
const shot = () => page.screenshot({ type: 'png', clip: { x: 0, y: 0, width: 1920, height: 1080 }, optimizeForSpeed: true });

const args = process.argv.slice(2);
if (args[0] === '--stills') {
  const dir = path.resolve(args[2] || path.join(here, 'out', 'stills'));
  mkdirSync(dir, { recursive: true });
  for (const s of args[1].split(',')) {
    await page.evaluate(t => window.renderFrame(t), parseFloat(s));
    writeFileSync(path.join(dir, `t${s.padStart(4, '0')}.png`), await shot());
  }
  console.log('截图在', dir);
} else {
  mkdirSync(path.join(here, 'out'), { recursive: true });
  const out = path.join(here, 'out', 'helperbot-promo.mp4');
  const ff = spawn(findFfmpeg(), ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'png', '-i', '-',
    '-c:v', 'libx264', '-preset', 'medium', '-crf', '16', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', out], { stdio: ['pipe', 'inherit', 'inherit'] });
  const done = new Promise((ok, bad) => ff.on('close', c => c === 0 ? ok() : bad(new Error('ffmpeg 退出码 ' + c))));
  const total = DUR * FPS, t0 = Date.now();
  for (let i = 0; i < total; i++) {
    await page.evaluate(t => window.renderFrame(t), i / FPS);
    if (!ff.stdin.write(await shot())) await new Promise(r => ff.stdin.once('drain', r));
    if (i % 300 === 0) console.log(`${i}/${total} 帧，${((Date.now() - t0) / 1000).toFixed(0)} 秒`);
  }
  ff.stdin.end();
  await done;
  console.log('完成：', out);
}
await browser.close();
