// 拼多多商品数据抓取：扫码登录 + 保存登录态 + 按链接抓取颜色规格
import { chromium } from 'playwright-core';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { info, warn, error, saveShot } from './log.mjs';

const __dir = path.dirname(fileURLToPath(import.meta.url));
const AUTH_FILE = path.join(__dir, '..', 'auth.json');
// 浏览器缓存目录：保留拼多多页面资源，第二次打开就不用重新下载
const CACHE_DIR = path.join(__dir, '..', '.browser-cache');

const MOBILE_UA =
  'Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
// 拼多多登录页只在桌面 UA 下提供「扫码登录」，移动 UA 只给手机号登录
const DESKTOP_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

const LAUNCH_ARGS = ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage'];

let lastDiag = '';   // 最近一次登录失败的诊断信息

let loginCtx = null;

export const getLastDiag = () => lastDiag;

export const hasAuth = () => {
  try {
    const c = JSON.parse(fs.readFileSync(AUTH_FILE, 'utf8')).cookies || [];
    return fs.existsSync(AUTH_FILE) && c.length > 0;
  } catch { return false; }
};

export function logout() {
  if (loginCtx) { try { loginCtx.ctx.close(); } catch {} loginCtx = null; }
  if (headfulBrowser && headfulBrowser.isConnected()) {
    try { headfulBrowser.close(); } catch {}
    headfulBrowser = null;
  }
  try { fs.rmSync(AUTH_FILE); } catch {}
}

// 有桌面环境才能弹出可见窗口；无显示的 Linux 容器退回头显模式
export function canShowWindow() {
  if (process.env.HEADLESS === '1') return false;
  if (process.platform === 'win32' || process.platform === 'darwin') return true;
  return !!process.env.DISPLAY;
}

let headlessBrowser = null;
let headfulBrowser = null;

async function launchBrowser(headless) {
  const opts = {
    headless,
    args: [...LAUNCH_ARGS, '--disable-blink-features=AutomationControlled'],
    ignoreDefaultArgs: ['--enable-automation'],
  };
  if (!headless) {
    try { fs.mkdirSync(CACHE_DIR, { recursive: true }); } catch {}
    opts.args.push('--disk-cache-dir=' + CACHE_DIR);
  }
  const tag = headless ? '[后台]' : '[窗口]';
  const exe = process.env.CHROME_PATH;
  if (exe && fs.existsSync(exe)) {
    info('启动浏览器(指定路径):', exe, tag);
    return chromium.launch({ ...opts, executablePath: exe });
  }
  let lastErr;
  for (const channel of ['chrome', 'msedge']) {
    try {
      info('尝试启动浏览器 channel=' + channel, tag);
      const b = await chromium.launch({ ...opts, channel });
      info('浏览器已启动 channel=' + channel);
      return b;
    }
    catch (e) { lastErr = e; warn('channel=' + channel + ' 启动失败:', e.message.split('\n')[0]); }
  }
  error('未找到可用浏览器', lastErr);
  throw new Error('未找到 Chrome/Edge 浏览器。请安装 Chrome，或用环境变量 CHROME_PATH 指定浏览器路径。' +
    (lastErr ? '（' + lastErr.message.split('\n')[0] + '）' : ''));
}

// 有桌面环境时用可见浏览器（拼多多反爬会拦无头浏览器），否则退回头显
async function getBrowser() {
  if (canShowWindow()) {
    if (headfulBrowser && headfulBrowser.isConnected()) return headfulBrowser;
    headfulBrowser = await launchBrowser(false);
    return headfulBrowser;
  }
  if (headlessBrowser && headlessBrowser.isConnected()) return headlessBrowser;
  headlessBrowser = await launchBrowser(true);
  return headlessBrowser;
}

async function newContext() {
  const b = await getBrowser();
  const opts = { viewport: { width: 420, height: 820 }, userAgent: MOBILE_UA, locale: 'zh-CN' };
  if (hasAuth()) opts.storageState = AUTH_FILE;
  return b.newContext(opts);
}
export function extractGoodsId(input) {
  if (!input) return null;
  const s = String(input).trim();
  let m = s.match(/[?&]goods_id=(\d+)/) || s.match(/[?&]goodsId=(\d+)/);
  if (m) return m[1];
  m = s.match(/^\s*(\d{6,})\s*$/);
  if (m) return m[1];
  return null;
}

/* ---------------- 登录 ---------------- */

// 可见窗口模式：弹出真实浏览器让用户自己登录（能过反爬、可手动处理滑块）
export async function startWindowLogin() {
  logout();
  info('开始登录流程（浏览器窗口模式）');
  const b = await getBrowser();
  const ctx = await b.newContext({
    viewport: { width: 460, height: 820 }, userAgent: DESKTOP_UA, locale: 'zh-CN',
  });
  const page = await ctx.newPage();
  loginCtx = { ctx, page, mode: 'window' };

  // 不阻塞请求：窗口在后台加载，用户自己登录，前端轮询登录结果
  page.goto('https://mobile.yangkeduo.com/login.html', { waitUntil: 'commit', timeout: 30000 })
    .then(() => page.bringToFront().catch(() => {}))
    .catch(e => warn('打开登录页出错:', e.message.split('\n')[0]));
  info('已打开浏览器窗口，等待用户登录');
  return { mode: 'window' };
}

// 页面当前状态（供前端提示）
export async function getLoginState() {
  if (!loginCtx) return null;
  const { page } = loginCtx;
  if (page.isClosed()) return null;
  return page.evaluate(() => {
    const t = document.body ? (document.body.innerText || '') : '';
    return {
      url: location.href,
      rendered: /扫码登录|手机登录|验证码/.test(t),
      onLogin: /\/login\.html/.test(location.href),
    };
  }).catch(() => null);
}

// 扫码登录（后台模式）：显示页面自己的二维码，由页面自身轮询完成登录
export async function startQrLogin() {
  logout();
  info('开始扫码登录流程');
  const ctx = await (await getBrowser()).newContext({
    viewport: { width: 460, height: 820 }, userAgent: DESKTOP_UA, locale: 'zh-CN',
  });
  const page = await ctx.newPage();
  loginCtx = { ctx, page, mode: 'qr' };

  page.on('response', async (res) => {
    if (!/cupid\/login\/check_qr_code/.test(res.url())) return;
    try {
      const j = await res.json();
      if (j && j.error_code) info('页面轮询:', j.error_code, j.error_msg || '');
      else if (j && j.result) info('页面轮询: 状态更新');
    } catch {}
  });

  try {
    await page.goto('https://mobile.yangkeduo.com/login.html', { waitUntil: 'commit', timeout: 30000 });
  } catch (e) {
    warn('打开登录页超时:', e.message.split('\n')[0]);
  }

  try {
    await page.waitForSelector('text=扫码登录', { timeout: 25000 });
    await page.getByText('扫码登录').first().click({ timeout: 5000 });
    info('已切到扫码登录');
  } catch (e) {
    warn('未能点击「扫码登录」:', e.message.split('\n')[0]);
  }

  for (let i = 0; i < 40; i++) {
    const src = await page.evaluate(() => {
      for (const img of document.querySelectorAll('img')) {
        const s = img.src || '';
        if (s.startsWith('data:image') && (img.naturalWidth >= 100 || img.width >= 100)) return s;
      }
      return '';
    }).catch(() => '');
    if (src && src.length > 500) {
      info('已取到页面二维码');
      lastDiag = '';
      return src;
    }
    await page.waitForTimeout(300);
  }

  try {
    const buf = await page.screenshot({ fullPage: false });
    const p = saveShot('login-fail.png', buf);
    const body = await page.evaluate(() => (document.body.innerText || '').slice(0, 200)).catch(() => '');
    lastDiag = 'URL=' + page.url() + ' | 页面文字=' + body.replace(/\n/g, ' ');
    error('未找到二维码。截图:', p, '|', lastDiag);
  } catch (e) { error('诊断截图失败:', e); }
  return null;
}

// 登录成功后不关浏览器：抓取复用它（带缓存所以快），也避免反复弹窗
async function hasLoginCookie(ctx) {
  const cookies = await ctx.cookies('https://mobile.yangkeduo.com');
  const t = cookies.find(c => c.name === 'PDDAccessToken');
  return !!(t && t.value);
}

export async function checkLogin() {
  if (!loginCtx) return { loggedIn: hasAuth() };
  const { ctx, page } = loginCtx;
  if (page.isClosed()) { loginCtx = null; return { loggedIn: hasAuth() }; }
  try {
    if (await hasLoginCookie(ctx)) {
      await ctx.storageState({ path: AUTH_FILE });
      try { await ctx.close(); } catch {}
      loginCtx = null;
      // 窗口模式登录完成后，关掉那个浏览器窗口，避免留下空白页
      if (headfulBrowser && headfulBrowser.isConnected()) {
        try { await headfulBrowser.close(); } catch {}
        headfulBrowser = null;
      }
      info('登录成功，登录态已保存');
      return { loggedIn: true };
    }
    return { loggedIn: false };
  } catch (e) {
    error('检查登录状态失败:', e);
    loginCtx = null;
    return { loggedIn: false, error: e.message };
  }
}

/* ---------------- 抓取商品颜色 ---------------- */

// 从 sku 数组里按 spec_key 分组，取颜色维度：优先选取“非纯数字”的那一组（颜色多为中文名，尺码多为数字）
function pickColors(skus) {
  if (!Array.isArray(skus) || !skus.length) return [];
  return groupSpecs(skus).colorValues;
}

// 按 spec_key 分组，返回颜色维度取值、颜色→SKU 图、最低拼单价
function groupSpecs(skus) {
  const groups = [];
  const idx = new Map();
  const colorImages = {};
  let minGroupPrice = null;
  for (const s of skus) {
    const specs = s.specs || s.spec || [];
    for (const sp of specs) {
      const key = sp.spec_key ?? sp.specKey ?? '__';
      const val = sp.spec_value ?? sp.specValue;
      if (val == null) continue;
      if (!idx.has(key)) { idx.set(key, groups.length); groups.push({ key, values: [] }); }
      const g = groups[idx.get(key)];
      if (!g.values.includes(val)) g.values.push(val);
    }
    const img = s.thumb_url || s.thumbUrl || s.image_url || '';
    const gp = s.group_price ?? s.groupPrice;
    if (gp != null && gp !== '' && gp !== 0) {
      const n = Number(gp);
      if (!Number.isNaN(n) && n > 0 && (minGroupPrice == null || n < minGroupPrice)) minGroupPrice = n;
    }
  }
  const isSizeLike = (g) => g.values.every(v => /^\s*\d+(\.\d+)?\s*$/.test(String(v)));
  const colorGroup = groups.find(g => !isSizeLike(g)) || groups[0];
  const colorValues = colorGroup ? colorGroup.values : [];
  // 颜色 → 图片：sku 的 specs 里含该颜色值时，记下该 sku 的图
  if (colorGroup) {
    for (const s of skus) {
      const specs = s.specs || s.spec || [];
      const img = s.thumb_url || s.thumbUrl || s.image_url || '';
      if (!img) continue;
      for (const sp of specs) {
        const key = sp.spec_key ?? sp.specKey ?? '__';
        const val = sp.spec_value ?? sp.specValue;
        if (key === colorGroup.key && val && colorValues.includes(val) && !colorImages[val]) colorImages[val] = img;
      }
    }
  }
  return { colorValues, colorImages, minGroupPrice };
}

export async function fetchByUrl(input) {
  const goodsId = extractGoodsId(input);
  if (!goodsId) throw new Error('链接里没找到商品编号（goods_id），请确认复制的是商品链接，或直接填商品编号');
  if (!hasAuth()) throw new Error('尚未登录，请先扫码登录');

  const ctx = await newContext();
  const page = await ctx.newPage();

  // 记录页面自身发出的 sku 接口响应（页面请求自带签名，最可靠）
  const seen = [];
  page.on('response', async (resp) => {
    const u = resp.url();
    if (!/\/api\//.test(u) || /\.(js|css|png|jpe?g|gif|webp|ttf|woff2?)/.test(u)) return;
    try {
      const txt = await resp.text();
      if (/"specs"|spec_value|specValue/.test(txt)) seen.push(txt);
    } catch {}
  });

  try {
    await page.goto(`https://mobile.yangkeduo.com/goods.html?goods_id=${goodsId}`,
      { waitUntil: 'domcontentloaded', timeout: 45000 });
    await page.waitForTimeout(4500);

    // 登录态失效：页面会被重定向到登录页
    if (/\/login\.html/.test(page.url())) {
      logout();
      throw new Error('登录态已过期，请点右上角重新扫码登录');
    }

    // 1) 从页面全局数据读
    let info = await page.evaluate(() => {
      const S = window.rawData && window.rawData.store && window.rawData.store.initDataObj;
      const g = S && S.goods;
      if (!g) return null;
      const skus = g.skus || g.sku || [];
      const groups = [];
      const idx = {};
      skus.forEach(s => (s.specs || s.spec || []).forEach(sp => {
        const k = sp.spec_key != null ? sp.spec_key : sp.specKey;
        const v = sp.spec_value != null ? sp.spec_value : sp.specValue;
        if (v == null || k == null) return;
        if (!(k in idx)) { idx[k] = groups.length; groups.push([]); }
        const arr = groups[idx[k]];
        if (!arr.includes(v)) arr.push(v);
      }));
      const isSizeLike = (arr) => arr.every(v => /^\s*\d+(\.\d+)?\s*$/.test(String(v)));
      const pick = groups.find(arr => !isSizeLike(arr)) || groups[0] || [];
      return { goodsId: g.goodsID || g.goods_id, goodsName: g.goodsName || g.goods_name, skuCount: skus.length, colors: pick };
    }).catch(() => null);

    // 2) 页面自身响应里提取（含签名）——可同时拿到 SKU 图与拼单价
    let rich = null;
    for (const txt of seen) {
      const got = collectFromPayload(txt);
      if (got.colors && got.colors.length) { rich = got; break; }
    }

    // 3) 页面内调 SKU 接口（借助页面已有的签名环境）
    if (!rich) {
      const api = await page.evaluate(async (gid) => {
        try {
          const r = await fetch('/proxy/api/api/oak/integration/render/sku', {
            method: 'POST', headers: { 'Content-Type': 'application/json' }, credentials: 'include',
            body: JSON.stringify({ goods_id: Number(gid), page_version: 7, _component_version: 2, page_from: 0, hostname: 'mobile.yangkeduo.com' }),
          });
          return await r.text();
        } catch (e) { return ''; }
      }, goodsId).catch(() => '');
      if (api) {
        const got = collectFromPayload(api);
        if (got.colors && got.colors.length) rich = got;
      }
    }

    // 合并颜色来源
    const colors = (rich && rich.colors && rich.colors.length) ? rich.colors
      : (info && info.colors ? info.colors : []);
    const goodsName = (rich && rich.goodsName) || (info && info.goodsName) || '';
    if (!colors.length) {
      throw new Error('没抓到颜色规格。可能原因：该商品规格在 App 内展示、商品已下架，或登录态已过期（可重新登录再试）。');
    }

    // 下载颜色 SKU 图（并行，失败不阻塞）
    const skuImages = {};
    const colorImages = (rich && rich.colorImages) || {};
    await Promise.all(colors.map(async (c) => {
      const url = colorImages[c];
      if (!url) return;
      const data = await downloadAsDataUri(url);
      if (data) skuImages[c] = data;
    }));

    // 主图（第一个颜色的图兼作主图兜底）
    let mainImage = (rich && rich.mainImage) || '';
    const mainData = await downloadAsDataUri(mainImage);
    mainImage = mainData || (colors.length ? (skuImages[colors[0]] || '') : '');

    return {
      goodsId: (info && info.goodsId) || goodsId,
      goodsName,
      colors,
      skuImages,
      mainImage,
      minGroupPrice: (rich && rich.minGroupPrice) || null,
    };
  } finally {
    try { await ctx.close(); } catch {}
  }
}

// 从任意 JSON 文本里尝试提取规格颜色（兼容 sku[]/skus[]、specs/spec 两种命名）
function collectFromPayload(text) {
  let j;
  try { j = JSON.parse(text); } catch { return { colors: [] }; }
  const roots = [j, j.goods, j.store && j.store.initDataObj && j.store.initDataObj.goods].filter(Boolean);
  let skus = [];
  for (const r of roots) {
    if (Array.isArray(r.sku) && r.sku.length) { skus = r.sku; break; }
    if (Array.isArray(r.skus) && r.skus.length) { skus = r.skus; break; }
    if (Array.isArray(r.skus) && r.skus[0] && Array.isArray(r.skus[0].specs) && r.skus[0].specs.length) { skus = r.skus; break; }
  }
  const { colorValues, colorImages, minGroupPrice } = groupSpecs(skus);
  const goods = j.goods || (j.store && j.store.initDataObj && j.store.initDataObj.goods) || {};
  const gallery = goods.top_gallery || goods.topGallery || goods.detail_gallery || goods.detailGallery || [];
  const mainImages = (Array.isArray(gallery) ? gallery : []).map(x => typeof x === 'string' ? x : (x && (x.url || x.img_url)) || '').filter(Boolean);
  const mainImage = mainImages[0] || goods.hd_thumb_url || goods.thumb_url || '';
  return { colors: colorValues, colorImages, goodsName: goods.goods_name || goods.goodsName || '', minGroupPrice, mainImage };
}

// 下载图片为 data URI（失败返回空串）
const MAX_IMG = 3 * 1024 * 1024;
async function downloadAsDataUri(url) {
  if (!url) return '';
  try {
    const r = await fetch(url, {
      headers: {
        'User-Agent': MOBILE_UA,
        'Referer': 'https://mobile.yangkeduo.com/',
        'Accept': 'image/avif,image/webp,image/apng,image/*,*/*;q=0.8',
      },
    });
    if (!r.ok) return '';
    const ct = (r.headers.get('content-type') || '').split(';')[0].trim();
    const buf = Buffer.from(await r.arrayBuffer());
    if (!buf.length || buf.length > MAX_IMG) return '';
    let mime = ct;
    if (!/^image\//.test(mime)) {
      // 按魔数兜底识别
      if (buf[0] === 0xFF && buf[1] === 0xD8) mime = 'image/jpeg';
      else if (buf[0] === 0x89 && buf[1] === 0x50) mime = 'image/png';
      else if (buf.slice(0, 4).toString('ascii') === 'RIFF') mime = 'image/webp';
      else return '';
    }
    return `data:${mime};base64,${buf.toString('base64')}`;
  } catch {
    return '';
  }
}

