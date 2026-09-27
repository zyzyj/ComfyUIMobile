// 本地服务：提供网页界面 + 登录态管理 + 按链接抓取商品颜色
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { startQrLogin, startWindowLogin, canShowWindow, checkLogin, getLoginState, fetchByUrl, logout, hasAuth, getLastDiag } from './lib/capture.mjs';
import { info, warn, error, tail, clear, LOG_FILE } from './lib/log.mjs';

const __dir = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT) || 8787;

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
};

function send(res, code, body, type = 'application/json; charset=utf-8') {
  res.writeHead(code, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  res.end(typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body));
}

async function readJson(req) {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  if (!chunks.length) return {};
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { return {}; }
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const p = url.pathname;

  try {
    // ---------- API ----------
    if (p === '/api/status') {
      return send(res, 200, { loggedIn: hasAuth() });
    }

    if (p === '/api/login/start' && req.method === 'POST') {
      info('收到登录请求');
      // 有桌面环境（用户自己的电脑）：弹出真实浏览器窗口，用户自己登录，最可靠
      if (canShowWindow()) {
        try {
          const r = await startWindowLogin();
          return send(res, 200, { ok: true, mode: r.mode });
        } catch (e) {
          error('打开登录窗口失败:', e);
          return send(res, 200, { ok: false, error: '打不开浏览器窗口：' + e.message });
        }
      }
      // 无桌面环境（后台容器）：回退到后台扫码
      let qr = null;
      try {
        qr = await startQrLogin();
      } catch (e) {
        error('登录流程异常:', e);
        return send(res, 200, { ok: false, error: e.message });
      }
      if (!qr) {
        warn('二维码为空，返回失败');
        const diag = getLastDiag();
        return send(res, 200, {
          ok: false,
          error: '没拿到二维码。详情：' + (diag || '（无）') + '\n已存截图 logs/login-fail.png，请把 logs 文件夹发我排查。',
        });
      }
      return send(res, 200, { ok: true, mode: 'qr', qr });
    }

    if (p === '/api/login/check' && req.method === 'POST') {
      const r = await checkLogin();
      if (r.loggedIn) {
        info('登录成功，登录态已保存');
        return send(res, 200, r);
      }
      // 未登录时附带页面状态，便于前端提示「页面加载中」
      const st = await getLoginState();
      return send(res, 200, { ...r, state: st });
    }

    if (p === '/api/logout' && req.method === 'POST') {
      logout();
      info('已退出登录');
      return send(res, 200, { ok: true });
    }

    if (p === '/api/fetch' && req.method === 'POST') {
      const { url: input } = await readJson(req);
      info('收到抓取请求:', input);
      try {
        const t0 = Date.now();
        const data = await fetchByUrl(input);
        info('抓取成功，用时', Date.now() - t0, 'ms；颜色', (data.colors || []).length, '个；图片', Object.keys(data.skuImages || {}).length, '张');
        return send(res, 200, { ok: true, ...data });
      } catch (e) {
        error('抓取失败:', e);
        return send(res, 200, { ok: false, error: e.message });
      }
    }

    if (p === '/api/logs') {
      return send(res, 200, { ok: true, text: tail(500), file: LOG_FILE });
    }

    if (p === '/api/logs/clear' && req.method === 'POST') {
      clear();
      info('日志已清空');
      return send(res, 200, { ok: true });
    }

    if (p === '/api/logs/download') {
      if (!fs.existsSync(LOG_FILE)) return send(res, 404, 'no log');
      const name = 'pdd-log-' + new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-') + '.txt';
      res.writeHead(200, {
        'Content-Type': 'text/plain; charset=utf-8',
        'Content-Disposition': 'attachment; filename="' + name + '"',
      });
      return res.end(fs.readFileSync(LOG_FILE));
    }

    // ---------- 静态文件 ----------
    let file = p === '/' ? '/index.html' : p;
    file = path.join(__dir, 'public', path.normalize(file).replace(/^(\.\.[/\\])+/, ''));
    if (!file.startsWith(path.join(__dir, 'public'))) return send(res, 403, { error: 'forbidden' });
    if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) return send(res, 404, { error: 'not found' });
    const ext = path.extname(file).toLowerCase();
    return send(res, 200, fs.readFileSync(file), MIME[ext] || 'application/octet-stream');
  } catch (e) {
    error('请求处理异常:', e);
    return send(res, 500, { ok: false, error: e.message });
  }
});

server.listen(PORT, () => {
  info('服务启动，端口', PORT);
  console.log('');
  console.log('  ========================================');
  console.log('   拼多多 / 1688 规格生成器  已启动');
  console.log('  ========================================');
  console.log('');
  console.log('   请用浏览器打开： http://localhost:' + PORT);
  console.log('');
  console.log('   出问题时点页面右上角「日志」，');
  console.log('   或把 logs 文件夹发给我。');
  console.log('');
  console.log('   日志文件： ' + LOG_FILE);
  console.log('');
  console.log('   保持本窗口打开；按 Ctrl+C 退出。');
  console.log('');
});
