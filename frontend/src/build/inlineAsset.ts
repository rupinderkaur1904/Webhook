/**
 * Vite inlines small assets as data: URIs, but the Content-Security-Policy allows fonts only from
 * 'self', so an inlined font subset would be refused by the browser. Fonts are always emitted as files.
 */
export function inlineAsset(filePath: string): boolean | undefined {
  return /\.(woff2?|ttf|otf)$/.test(filePath) ? false : undefined;
}
