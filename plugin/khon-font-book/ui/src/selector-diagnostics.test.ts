import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { diagnose, publicPageUrl } from './selector-diagnostics'

describe('selector diagnostics', () => {
  beforeEach(() => {
    vi.stubGlobal('CSS', { escape: (text: string) => text })
    document.head.innerHTML = ''
    document.body.innerHTML = '<section class="hero"><h1 class="headline">绿野标题</h1><p class="sub">副标题</p></section>'
  })
  afterEach(() => vi.unstubAllGlobals())
  it('recovers a nonexistent middle class without claiming the original chain matched', () => {
    const result = diagnose(document, '.hero .title .headline')
    expect(result.matches).toBe(0)
    expect(result.recovered).toBe(true)
    expect(result.candidates.some(c => c.selector === '.hero > .headline' && c.text === '绿野标题')).toBe(true)
    expect(document.querySelectorAll('style')).toHaveLength(0)
  })
  it('distinguishes comma targets, descendants, and multiple classes on the same element', () => {
    expect(diagnose(document, '.headline, .sub').matches).toBe(2)
    expect(diagnose(document, '.hero .headline').matches).toBe(1)
    expect(diagnose(document, '.hero.headline').matches).toBe(0)
  })
  it('reports unmatched or invalid selectors and bounds traversal', () => {
    expect(diagnose(document, '#absent').candidates).toHaveLength(0)
    expect(() => diagnose(document, '.bad[')).toThrow()
    document.body.innerHTML = '<main>' + Array.from({ length: 100 }, (_, i) => `<p id="p${i}">text</p>`).join('') + '</main>'
    expect(diagnose(document, 'main').candidates.length).toBeLessThanOrEqual(60)
    expect(document.querySelectorAll('style')).toHaveLength(0)
  })
  it('limits preview URLs to same-origin public documents, including encoded paths', () => {
    expect(publicPageUrl('/', 'http://localhost:8090')).toBe('http://localhost:8090/')
    for (const url of ['https://other.test/', '/console', '/console%2Fplugins', '/apis/x', 'javascript:alert(1)', 'http://user:pass@localhost:8090/']) {
      expect(() => publicPageUrl(url, 'http://localhost:8090')).toThrow()
    }
  })
})
