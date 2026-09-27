// 构建模板数据：把拼多多模板与两个 1688 模板的部件内嵌到 public/
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const __dir = path.dirname(fileURLToPath(import.meta.url));
const SRC_DIR = path.join(__dir, '..');
const OUT_DIR = path.join(__dir, 'public');

/* ---------------- 最小 zip 读取器 ---------------- */
function readZip(b) {
  const out = [];
  let eocd = -1;
  for (let i = b.length - 22; i >= 0; i--) {
    if (b.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('not a zip');
  const count = b.readUInt16LE(eocd + 10);
  let off = b.readUInt32LE(eocd + 16);
  for (let n = 0; n < count; n++) {
    if (b.readUInt32LE(off) !== 0x02014b50) throw new Error('bad central dir');
    const compMethod = b.readUInt16LE(off + 10);
    const compSize = b.readUInt32LE(off + 20);
    const nameLen = b.readUInt16LE(off + 28);
    const extraLen = b.readUInt16LE(off + 30);
    const commentLen = b.readUInt16LE(off + 32);
    const localOff = b.readUInt32LE(off + 42);
    const name = b.slice(off + 46, off + 46 + nameLen).toString('utf8');
    const lnameLen = b.readUInt16LE(localOff + 26);
    const lextraLen = b.readUInt16LE(localOff + 28);
    const dataStart = localOff + 30 + lnameLen + lextraLen;
    const raw = b.slice(dataStart, dataStart + compSize);
    const data = compMethod === 0 ? raw : zlib.inflateRawSync(raw);
    if (!name.endsWith('/')) out.push({ name, data });
    off += 46 + nameLen + extraLen + commentLen;
  }
  return out;
}

/* ---------------- 拼多多模板 ---------------- */
function buildPdd() {
  const buf = fs.readFileSync(path.join(SRC_DIR, '规格批量编辑.xlsx'));
  const entries = readZip(buf);
  const GEN = new Set(['xl/sharedStrings.xml', 'xl/worksheets/sheet1.xml']);
  const order = [], tpl = {};
  for (const e of entries) {
    order.push(e.name);
    if (GEN.has(e.name)) continue;
    tpl[e.name] = e.data.toString('base64');
  }
  const ssXml = entries.find(e => e.name === 'xl/sharedStrings.xml').data.toString('utf8');
  const si = [...ssXml.matchAll(/<si>(.*?)<\/si>/gs)];
  const PROMPT = si[1][1].replace(/^<t>/, '').replace(/<\/t>$/, '');
  if (!PROMPT.includes('状态为')) throw new Error('拼多多提示语提取失败');
  return { tpl, order, prompt: PROMPT };
}

/* ---------------- 1688 模板 ---------------- */
// 保留表头行，数据行从 startRow 开始追加；返回 sheet/shared 原文 + 其他部件
function build1688(file, startRow, ncols) {
  const buf = fs.readFileSync(path.join(SRC_DIR, file));
  const entries = readZip(buf);
  const sheetEntry = entries.find(e => /^xl\/worksheets\/sheet1\.xml$/.test(e.name));
  const sharedEntry = entries.find(e => e.name === 'xl/sharedStrings.xml');
  const order = [], statics = {};
  for (const e of entries) {
    if (e.name === sheetEntry.name || e.name === sharedEntry.name) continue;
    order.push(e.name);
    statics[e.name] = e.data.toString('base64');
  }
  return {
    sheetXml: sheetEntry.data.toString('utf8'),
    sharedXml: sharedEntry ? sharedEntry.data.toString('utf8') : null,
    statics, order, startRow, ncols,
  };
}

const pdd = buildPdd();
const aliGoods = build1688('商品批量导入模板.xlsx', 6, 16);
const aliSku = build1688('SKU规格导入模板.xlsx', 5, 4);

fs.writeFileSync(path.join(OUT_DIR, 'template.json'), JSON.stringify(pdd));
fs.writeFileSync(path.join(OUT_DIR, 'template_1688.json'), JSON.stringify({ goods: aliGoods, sku: aliSku }));
console.log('template.json        : 拼多多', Object.keys(pdd.tpl).length, '部件, prompt', pdd.prompt.length);
console.log('template_1688.json   : 商品批量导入', Object.keys(aliGoods.statics).length, '部件, 数据起于第', aliGoods.startRow, '行,', aliGoods.ncols, '列');
console.log('                       SKU规格导入', Object.keys(aliSku.statics).length, '部件, 数据起于第', aliSku.startRow, '行,', aliSku.ncols, '列');
