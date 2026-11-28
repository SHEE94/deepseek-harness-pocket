import { defineTool } from '@deepseek-ai/dsh-tools'
import fs from 'node:fs'

const PORT = 3081
export const name = 'dsh-android-bridge'
export const inject = ['tools']

function token() {
  try { return fs.readFileSync('/root/.dsh/native-token.txt', 'utf8').trim() } catch { return '' }
}

async function call(path, body) {
  const res = await fetch(`http://127.0.0.1:${PORT}${path}`, {
    method: body ? 'POST' : 'GET',
    headers: { 'X-Token': token(), 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`原生桥 ${res.status}: ${text}`)
  return JSON.parse(text)
}

export function apply(ctx, config = {}) {
  if (config.enabled === false) return
  const register = (spec) => ctx.tools.register(defineTool({
    name: spec.name,
    description: spec.description,
    parameters: spec.parameters,
    output: {
      schema: { type: 'json' },
      render: (_args, value) => [{ type: 'text', text: value.text ?? JSON.stringify(value) }],
    },
    execute: async (args) => spec.run(args),
  }))

  register({
    name: 'android_state',
    description: '查询安卓原生桥状态（无障碍服务是否已启用、截屏能力）',
    parameters: {},
    run: async () => ({ text: JSON.stringify(await call('/health')) }),
  })

  register({
    name: 'android_screen_tree',
    description: '读取当前手机屏幕的控件树（类名/文本/坐标边界/可点击性）。先调用它，再决定 android_tap 的坐标。',
    parameters: {},
    run: async () => {
      const t = await call('/tree')
      return { text: `pkg=${t.pkg} nodes=${t.nodes}\n` + JSON.stringify(t.tree).slice(0, 6000) }
    },
  })

  register({
    name: 'android_screenshot',
    description: '截取手机屏幕保存为 /root/sdcard/dsh-screen.png（需要 Android 11+ 且无障碍已启用）',
    parameters: {},
    run: async () => {
      const res = await fetch(`http://127.0.0.1:${PORT}/screenshot`, { headers: { 'X-Token': token() } })
      if (!res.ok) throw new Error(`原生桥 ${res.status}: ${await res.text()}`)
      const buf = Buffer.from(await res.arrayBuffer())
      fs.writeFileSync('/root/sdcard/dsh-screen.png', buf)
      return { text: `截图已保存：/root/sdcard/dsh-screen.png（${buf.length} 字节）` }
    },
  })

  register({
    name: 'android_tap',
    description: '模拟点击手机屏幕坐标 (x, y)。坐标来自 android_screen_tree 的 bounds。',
    parameters: {
      x: { type: 'number', description: '屏幕 X 坐标' },
      y: { type: 'number', description: '屏幕 Y 坐标' },
    },
    run: async (a) => ({ text: JSON.stringify(await call('/tap', { x: Math.round(a.x), y: Math.round(a.y) })) }),
  })

  register({
    name: 'android_swipe',
    description: '模拟滑动手势（起点到终点）',
    parameters: {
      x1: { type: 'number', description: '起点 X' },
      y1: { type: 'number', description: '起点 Y' },
      x2: { type: 'number', description: '终点 X' },
      y2: { type: 'number', description: '终点 Y' },
      ms: { type: 'number', description: '时长毫秒，默认 300' },
    },
    run: async (a) => ({ text: JSON.stringify(await call('/swipe', a)) }),
  })

  register({
    name: 'android_longpress',
    description: '模拟长按 (x, y)',
    parameters: {
      x: { type: 'number', description: 'X' },
      y: { type: 'number', description: 'Y' },
    },
    run: async (a) => ({ text: JSON.stringify(await call('/longpress', a)) }),
  })

  register({
    name: 'android_text',
    description: '向当前焦点输入框输入文字（无障碍 setText；需输入框已获得焦点）',
    parameters: {
      text: { type: 'string', description: '要输入的文字' },
    },
    run: async (a) => ({ text: JSON.stringify(await call('/text', { text: String(a.text ?? '') })) }),
  })

  register({
    name: 'android_key',
    description: '按系统键：back（返回）/ home（桌面）/ recents（最近任务）/ notifications（下拉通知）',
    parameters: {
      key: { type: 'string', description: 'back | home | recents | notifications' },
    },
    run: async (a) => ({ text: JSON.stringify(await call('/key', { key: String(a.key ?? '') })) }),
  })

  console.log('[android-bridge] tools registered (8)')
}
