/**
 * jsonc.mjs —— manifest.json（JSONC：带 // 与 /* *\/ 注释）的读与"外科手术式"改写
 *
 * 为什么手写：uni-app 的 node_modules 是指向主构建区的 junction，不能 npm install，
 * 所以这里**只用 Node 内置模块**（fs / path / zlib）。@dcloudio 那边用 jsonc-parser，
 * 说明工具链本身容忍注释 —— 我们读它、改它，但绝不 JSON.parse + JSON.stringify 回写，
 * 那样会把注释和键顺序一起抹掉。
 *
 * 改写方式：解析时记录每个 value token 在原文里的 [valueStart, valueEnd) 偏移，
 * 替换时只 splice 那一小段字节，其余（注释、缩进、空行、键序）逐字节保持不变。
 */

import fs from 'node:fs'
import path from 'node:path'

const WS = new Set([' ', '\t', '\n', '\r', '\f', '\v'])

class JsoncError extends Error {}

function skipTrivia (s, i) {
  while (i < s.length) {
    const c = s[i]
    if (WS.has(c)) { i++; continue }
    if (c === '/' && s[i + 1] === '/') {
      i += 2
      while (i < s.length && s[i] !== '\n') i++
      continue
    }
    if (c === '/' && s[i + 1] === '*') {
      i += 2
      while (i < s.length && !(s[i] === '*' && s[i + 1] === '/')) i++
      if (i >= s.length) throw new JsoncError('块注释没有闭合')
      i += 2
      continue
    }
    break
  }
  return i
}

function readString (s, i) {
  // s[i] === '"'
  const start = i
  i++
  while (i < s.length) {
    const c = s[i]
    if (c === '\\') { i += 2; continue }
    if (c === '"') return { value: JSON.parse(s.slice(start, i + 1)), raw: s.slice(start, i + 1), start, end: i + 1, next: i + 1 }
    i++
  }
  throw new JsoncError(`字符串没有闭合（第 ${lineAt(s, start)} 行附近）`)
}

function readNumber (s, i) {
  const start = i
  while (i < s.length && /[0-9eE+\-.]/.test(s[i])) i++
  return { value: Number(s.slice(start, i)), raw: s.slice(start, i), start, end: i, next: i }
}

function readLiteral (s, i) {
  for (const [word, value] of [['true', true], ['false', false], ['null', null]]) {
    if (s.startsWith(word, i)) return { value, raw: word, start: i, end: i + word.length, next: i + word.length }
  }
  throw new JsoncError(`无法解析的 token：${s.slice(i, i + 12)}（第 ${lineAt(s, i)} 行）`)
}

function parse (s, i) {
  i = skipTrivia(s, i)
  const c = s[i]
  if (c === '{') return parseObject(s, i)
  if (c === '[') return parseArray(s, i)
  if (c === '"') {
    const r = readString(s, i)
    return { type: 'string', ...r }
  }
  if (c === '-' || (c >= '0' && c <= '9')) {
    const r = readNumber(s, i)
    return { type: 'number', ...r }
  }
  const r = readLiteral(s, i)
  return { type: 'literal', ...r }
}

function parseObject (s, i) {
  const start = i
  i = skipTrivia(s, i + 1)
  const children = []
  if (s[i] === '}') return { type: 'object', start, end: i + 1, children, next: i + 1 }
  for (;;) {
    i = skipTrivia(s, i)
    if (s[i] === '}') break // 容忍尾随逗号
    if (s[i] !== '"') throw new JsoncError(`对象的键必须是带引号的字符串（第 ${lineAt(s, i)} 行）`)
    const key = readString(s, i)
    i = skipTrivia(s, key.next)
    if (s[i] !== ':') throw new JsoncError(`键 "${key.value}" 后面缺冒号（第 ${lineAt(s, key.start)} 行）`)
    const value = parse(s, i + 1)
    children.push({ name: key.value, keyStart: key.start, keyEnd: key.end, node: value })
    i = skipTrivia(s, value.end)
    if (s[i] === ',') { i = skipTrivia(s, i + 1); continue }
    if (s[i] === '}') break
    throw new JsoncError(`对象里 "${key.name ?? ''}" 之后期望 , 或 }（第 ${lineAt(s, i)} 行）`)
  }
  return { type: 'object', start, end: i + 1, children, next: i + 1 }
}

function parseArray (s, i) {
  const start = i
  const items = []
  i = skipTrivia(s, i + 1)
  if (s[i] === ']') return { type: 'array', start, end: i + 1, items, next: i + 1 }
  for (;;) {
    const node = parse(s, i)
    items.push(node)
    i = skipTrivia(s, node.end)
    if (s[i] === ',') { i = skipTrivia(s, i + 1); if (s[i] === ']') break; continue }
    if (s[i] === ']') break
    throw new JsoncError(`数组里期望 , 或 ]（第 ${lineAt(s, i)} 行）`)
  }
  return { type: 'array', start, end: i + 1, items, next: i + 1 }
}

/** 1-based 行号 */
export function lineAt (text, offset) {
  let line = 1
  for (let i = 0; i < Math.min(offset, text.length); i++) if (text[i] === '\n') line++
  return line
}

export function parseJsonc (text) {
  const node = parse(text, 0)
  const rest = skipTrivia(text, node.end)
  if (rest !== text.length) throw new JsoncError(`文件尾部有多余内容（第 ${lineAt(text, rest)} 行）`)
  return node
}

/** 按路径取节点；路径元素是对象键（字符串）或数组下标（数字） */
export function getNode (root, segs) {
  let cur = root
  for (const seg of segs) {
    if (cur.type === 'object') {
      const hit = cur.children.find((c) => c.name === seg)
      if (!hit) return null
      cur = hit.node
    } else if (cur.type === 'array') {
      const idx = Number(seg)
      if (!Number.isInteger(idx) || !cur.items[idx]) return null
      cur = cur.items[idx]
    } else return null
  }
  return cur
}

export function toPlain (node) {
  if (!node) return undefined
  if (node.type === 'object') {
    const out = {}
    for (const c of node.children) out[c.name] = toPlain(c.node)
    return out
  }
  if (node.type === 'array') return node.items.map(toPlain)
  return node.value
}

/** 只替换该节点 value 的原始字节，注释/缩进/键序原样保留 */
export function replaceValue (text, node, newRaw) {
  return text.slice(0, node.start) + newRaw + text.slice(node.end)
}

/**
 * 在某个对象节点里插入一个新键。缩进沿用该对象第一个键所在行的前导空白，
 * 只插入，不重排 —— 其余字节（注释、空行、键顺序）逐字保留。
 * value 传 JS 值，内部 JSON.stringify(…, null, 2) 后按层级重缩进。
 */
export function insertKeyIntoObject (text, objNode, name, value) {
  const valueRaw = JSON.stringify(value, null, 2)
  const first = objNode.children[0]
  if (!first) {
    const body = valueRaw.replace(/\n/g, '\n  ')
    return text.slice(0, objNode.start + 1) + `\n  ${JSON.stringify(name)}: ${body}\n` + text.slice(objNode.end - 1)
  }
  const leadStart = text.lastIndexOf('\n', first.keyStart - 1) + 1
  const lead = text.slice(leadStart, first.keyStart) // 第一个键的前导缩进
  if (!/^\s*$/.test(lead)) throw new Error('对象首个键不在行首，无法安全插入（请手工编辑 manifest）')
  const body = valueRaw.replace(/\n/g, `\n${lead}`)
  const insert = `${lead}${JSON.stringify(name)}: ${body},\n`
  return text.slice(0, leadStart) + insert + text.slice(leadStart)
}

export function formatPath (segs) {
  return segs.map((s) => (typeof s === 'number' ? `[${s}]` : String(s))).join('.')
}

export function quoteLike (rawValue, newText) {
  // 沿用原文的引号/前导空格风格
  const m = /^(\s*)(["'])/.exec(rawValue)
  const lead = m ? m[1] : ''
  return `${lead}${JSON.stringify(newText)}`
}

export function readManifest (projectRoot) {
  const file = path.join(projectRoot, 'src', 'manifest.json')
  if (!fs.existsSync(file)) throw new Error(`找不到 manifest：${file}`)
  const text = fs.readFileSync(file, 'utf8')
  return { file, text, root: parseJsonc(text) }
}

export { JsoncError }
