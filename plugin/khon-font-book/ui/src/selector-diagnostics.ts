export type Candidate = { selector: string; text: string; count: number; family: string; normal: boolean; force: boolean; visible: boolean }
export type Diagnosis = { matches: number; recovered: boolean; candidates: Candidate[]; notes: string[]; injected: boolean }

export function publicPageUrl(input: string, origin: string) {
  const url = new URL(input, origin)
  if (url.origin !== origin || url.username || url.password || !['http:', 'https:'].includes(url.protocol)
    || /^\/(console|apis|api|uc|login|logout|system|actuator)(\/|$)/.test(decodeURIComponent(url.pathname))) {
    throw new Error('只允许本站公开主题页面；不能使用外站、后台或 API 地址。')
  }
  return url.href
}

// A computed-style probe only changes the isolated preview document, then removes
// its temporary sheet. It never edits the theme or publishes a site rule.
export function diagnose(doc: Document, input: string): Diagnosis {
  if (!input.trim() || input.length > 1024) throw new Error('请输入不超过 1024 字符的选择器。')
  const win = doc.defaultView
  if (!win || !doc.head || !doc.body) throw new Error('预览页尚未就绪。')
  const notes: string[] = []
  let original: Element[]
  try { original = Array.from(doc.querySelectorAll(input)) }
  catch { throw new Error('CSS 选择器语法无效；多个目标请用半角逗号，后代关系用空格。') }
  let roots = original
  let recovered = false
  if (!roots.length) {
    // Failed descendant chains often contain a nonexistent intermediate class.
    // Try their simple ID/class tokens; candidates are independently tested.
    const tokens = input.match(/[#.](?:\\.|[\w\u0080-\uffff-])+/g) || []
    roots = [...new Set(tokens.flatMap(token => {
      try { return Array.from(doc.querySelectorAll(token)).slice(0, 50) } catch { return [] }
    }))].slice(0, 50)
    recovered = roots.length > 0
    notes.push(recovered ? '原选择器未匹配。已按其中的 class/ID 查找相关元素；候选不是原选择器的匹配结果。' : '未找到目标；请检查拼写、当前页面及动态内容是否已加载。')
  }
  const escape = (value: string) => CSS.escape(value)
  const candidates: Candidate[] = []
  const seen = new Set<string>()
  const sheetNode = doc.createElement('style')
  const probeFamily = `kfb-probe-${Date.now()}`
  const injection = doc.querySelector('link[data-khon-font-book]')
  if (injection) injection.after(sheetNode)
  else { doc.head.appendChild(sheetNode); notes.push('尚无注入链接：普通覆盖按 head 末尾模拟，启用注入后请重新诊断。') }
  try {
    const sheet = sheetNode.sheet
    if (!sheet) throw new Error('预览页阻止临时样式，无法验证覆盖能力。')
    let visited = 0
    outer: for (const root of roots.slice(0, 50)) {
      const walker = doc.createTreeWalker(root, 1)
      let el: Element | null = root
      while (el) {
        if (++visited > 2000 || candidates.length >= 60) { notes.push('已达到扫描上限（2000 元素 / 60 候选），请缩小选择器范围。'); break outer }
        if (el.shadowRoot) notes.push('检测到 Shadow DOM：主题普通 CSS 无法穿透其样式边界，需组件专用适配。')
        if (el.localName === 'iframe') notes.push('检测到 iframe：其内部属于独立文档，本页主题规则不能直接作用。')
        const text = Array.from(el.childNodes).filter(n => n.nodeType === 3).map(n => n.textContent).join('').trim()
        if (text && !['script', 'style', 'noscript', 'option'].includes(el.localName)) {
          const selectors: string[] = []
          if (el.id) selectors.push(`#${escape(el.id)}`)
          const classes = Array.from(el.classList).slice(0, 5).map(c => `.${escape(c)}`).join('')
          if (classes) selectors.push(`${el.localName}${classes}`)
          const simple = el.id ? `#${escape(el.id)}` : classes || el.localName
          if (el !== root) {
            const anchor = root.id ? `#${escape(root.id)}` : Array.from(root.classList).slice(0, 3).map(c => `.${escape(c)}`).join('') || root.localName
            selectors.push(`${anchor}${el.parentElement === root ? ' > ' : ' '}${simple}`)
          }
          if (!selectors.length) selectors.push(el.localName)
          const computed = win.getComputedStyle(el)
          const family = computed.fontFamily
          const visible = computed.display !== 'none' && computed.visibility !== 'hidden' && computed.opacity !== '0' && !!el.getClientRects().length
          for (const selector of selectors) {
            if (seen.has(selector)) continue
            seen.add(selector)
            const probe = (force: boolean) => {
              const index = sheet.insertRule(`${selector} { font-family: "${probeFamily}", monospace${force ? ' !important' : ''}; transition: none !important; }`, sheet.cssRules.length)
              try { return win.getComputedStyle(el!).fontFamily.includes(probeFamily) }
              finally { sheet.deleteRule(index) }
            }
            candidates.push({ selector, text: text.slice(0, 100), count: doc.querySelectorAll(selector).length, family, visible, normal: probe(false), force: probe(true) })
            if (candidates.length >= 60) break
          }
        }
        el = walker.nextNode() as Element | null
      }
    }
  } finally { sheetNode.remove() }
  notes.push('覆盖测试针对所列文字样本的计算 font-family；匹配数量是整体范围，不保证每个匹配元素均可覆盖。不等同于实际字体下载、缺字覆盖或最终渲染成功。候选不会自动保存。')
  return { matches: original.length, recovered, candidates, notes: [...new Set(notes)], injected: !!doc.querySelector('link[data-khon-font-book]') }
}
