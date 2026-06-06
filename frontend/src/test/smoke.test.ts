describe('runner smoke test', () => {
  it('runs the test environment and jest-dom matchers', () => {
    expect(1 + 1).toBe(2)
    const el = document.createElement('div')
    el.textContent = 'hello'
    document.body.appendChild(el)
    expect(el).toBeInTheDocument()
    document.body.removeChild(el)
  })
})
