/**
 * Testing Library text matcher on an element's full textContent (version and SHA live in child <bdi> elements, so
 * the default own-text-node matching cannot see the whole string). Matches the innermost element whose entire
 * text equals `copy`, so the assertion stays exact.
 */
export function byFullText(copy: string) {
  return (_content: string, el: Element | null): boolean =>
    el !== null && el.textContent === copy && !Array.from(el.children).some((c) => c.textContent === copy);
}
