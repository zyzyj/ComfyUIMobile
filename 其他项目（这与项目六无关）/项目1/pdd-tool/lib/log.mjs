// 简易日志：同时输出到控制台与 logs/app.log
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dir = path.dirname(fileURLToPath(import.meta.url));
export const LOG_DIR = path.join(__dir, '..', 'logs');
export const LOG_FILE = path.join(LOG_DIR, 'app.log');

let stream = null;
function getStream() {
  if (stream) return stream;
  try {
    fs.mkdirSync(LOG_DIR, { recursive: true });
    stream = fs.createWriteStream(LOG_FILE, { flags: 'a' });
  } catch { stream = null; }
  return stream;
}

function fmt(v) {
  if (v instanceof Error) return v.stack || (v.name + ': ' + v.message);
  if (typeof v === 'string') return v;
  try { return JSON.stringify(v); } catch { return String(v); }
}

export function log(level, ...args) {
  const line = '[' + new Date().toISOString() + '] [' + level + '] ' + args.map(fmt).join(' ');
  try { console.log(line); } catch {}
  const s = getStream();
  if (s) { try { s.write(line + '\n'); } catch {} }
  return line;
}

export const info = (...a) => log('INFO', ...a);
export const warn = (...a) => log('WARN', ...a);
export const error = (...a) => log('ERROR', ...a);

export function tail(n = 400) {
  try {
    const txt = fs.readFileSync(LOG_FILE, 'utf8');
    const lines = txt.split('\n');
    return lines.slice(Math.max(0, lines.length - n)).join('\n');
  } catch { return ''; }
}

export function clear() {
  try { fs.writeFileSync(LOG_FILE, ''); } catch {}
}

// 保存诊断截图到 logs/ 目录
export function saveShot(name, buf) {
  try {
    fs.mkdirSync(LOG_DIR, { recursive: true });
    const p = path.join(LOG_DIR, name);
    fs.writeFileSync(p, buf);
    return p;
  } catch { return ''; }
}
