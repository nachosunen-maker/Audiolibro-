import JSZip from 'jszip';
import { NarrationEstimator } from '../domain/models';

export interface ParsedEpubChapter {
  chapterIndex: number;
  title: string;
  textContent: string;
  charCount: number;
  wordCount: number;
  estimatedDurationMs: number;
}

export interface ParsedEpubResult {
  title: string;
  author: string;
  language: string;
  description: string;
  sha256: string;
  coverBytes: Uint8Array | null;
  coverMimeType: string | null;
  chapters: ParsedEpubChapter[];
  totalCharCount: number;
  estimatedDurationMs: number;
}

export class EpubParseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'EpubParseError';
  }
}

/**
 * Calcula SHA-256 en hexadecimal para detectar libros duplicados de forma determinista.
 */
export async function computeSha256Hex(data: ArrayBuffer | Uint8Array): Promise<string> {
  const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
  if (typeof crypto !== 'undefined' && crypto.subtle) {
    const copy = new Uint8Array(bytes.byteLength);
    copy.set(bytes);
    const hashBuffer = await crypto.subtle.digest('SHA-256', copy.buffer);
    return Array.from(new Uint8Array(hashBuffer))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('');
  }
  // Fallback determinista para entornos sin crypto.subtle (solo pruebas locales sin HTTPS).
  let h1 = 0xdeadbeef ^ bytes.byteLength;
  let h2 = 0x41c6ce57 ^ bytes.byteLength;
  for (let i = 0; i < bytes.byteLength; i++) {
    const ch = bytes[i];
    h1 = Math.imul(h1 ^ ch, 2654435761);
    h2 = Math.imul(h2 ^ ch, 1597334677);
  }
  return `${(h1 >>> 0).toString(16).padStart(8, '0')}${(h2 >>> 0).toString(16).padStart(8, '0')}`;
}

/**
 * Parsea un archivo EPUB (EPUB 2 y EPUB 3) completamente en local en el navegador.
 * Extrae metadatos, portada, tabla de contenidos (NAV / NCX) y capítulos limpios.
 */
export async function parseEpubFile(
  fileBuffer: ArrayBuffer | Uint8Array,
  fallbackFileName: string,
): Promise<ParsedEpubResult> {
  const bytes = fileBuffer instanceof Uint8Array ? fileBuffer : new Uint8Array(fileBuffer);
  if (bytes.byteLength < 4) {
    throw new EpubParseError('El archivo EPUB está vacío o dañado.');
  }

  let zip: JSZip;
  try {
    zip = await JSZip.loadAsync(bytes);
  } catch {
    throw new EpubParseError('No se pudo abrir el archivo. Comprueba que sea un EPUB válido.');
  }

  // Verificar DRM (encryption.xml)
  const encryptionEntry = zip.file('META-INF/encryption.xml');
  if (encryptionEntry) {
    const encXml = await encryptionEntry.async('string');
    if (
      encXml.includes('EncryptedData') &&
      !encXml.includes('http://www.idpf.org/2008/embedding') &&
      !encXml.includes('http://ns.adobe.com/pdf/enc#RC')
    ) {
      throw new EpubParseError(
        'Este EPUB está protegido con DRM y no puede convertirse a audiolibro.',
      );
    }
  }

  const containerEntry = zip.file('META-INF/container.xml');
  if (!containerEntry) {
    throw new EpubParseError('El archivo EPUB no contiene META-INF/container.xml.');
  }

  const containerXml = await containerEntry.async('string');
  const opfPath = extractOpfPath(containerXml);
  if (!opfPath) {
    throw new EpubParseError('No se encontró la ruta al archivo OPF dentro del EPUB.');
  }

  const opfEntry = findZipEntry(zip, opfPath);
  if (!opfEntry) {
    throw new EpubParseError(`No se encontró el manifiesto OPF (${opfPath}).`);
  }

  const opfXml = await opfEntry.async('string');
  const opfDir = getDirectoryPrefix(opfPath);
  const parser = new DOMParser();
  const opfDoc = parser.parseFromString(opfXml, 'application/xml');

  const title =
    firstTextByLocalName(opfDoc, 'title') ||
    fallbackFileName.replace(/\.epub$/i, '').trim() ||
    'Libro sin título';
  const author = firstTextByLocalName(opfDoc, 'creator') || 'Autor desconocido';
  const language = (firstTextByLocalName(opfDoc, 'language') || 'es').toLowerCase();
  const description = cleanPlainText(firstTextByLocalName(opfDoc, 'description') || '');

  // Construir mapa de manifiesto
  const manifestMap = new Map<
    string,
    { id: string; href: string; mediaType: string; properties: string }
  >();
  const manifestItems = getElementsByLocalName(opfDoc, 'item');
  for (const item of manifestItems) {
    const id = item.getAttribute('id') || '';
    const href = item.getAttribute('href') || '';
    const mediaType = item.getAttribute('media-type') || '';
    const properties = item.getAttribute('properties') || '';
    if (id && href) {
      manifestMap.set(id, { id, href: decodeSafeUri(href), mediaType, properties });
    }
  }

  // Extraer portada
  const { coverBytes, coverMimeType } = await extractCoverImage(
    zip,
    opfDoc,
    opfDir,
    manifestMap,
  );

  // Extraer mapa de títulos desde TOC (EPUB 3 nav o EPUB 2 ncx)
  const tocTitles = await extractTocTitles(zip, opfDoc, opfDir, manifestMap);

  // Recorrer spine
  const spineRefs = getElementsByLocalName(opfDoc, 'itemref');
  const chapters: ParsedEpubChapter[] = [];

  for (const ref of spineRefs) {
    const idref = ref.getAttribute('idref');
    if (!idref) continue;
    const item = manifestMap.get(idref);
    if (!item) continue;
    if (
      !item.mediaType.includes('html') &&
      !item.href.match(/\.(xhtml|html|htm)$/i)
    ) {
      continue;
    }

    // Saltar documento de navegación puro en EPUB 3 si solo es índice
    if (item.properties.split(/\s+/).includes('nav')) {
      continue;
    }

    const fullPath = resolveRelativePath(opfDir, item.href);
    const entry = findZipEntry(zip, fullPath);
    if (!entry) continue;

    const html = await entry.async('string');
    const extracted = extractChapterTextAndHeading(html);
    if (extracted.text.length < 20) {
      // Omitir páginas vacías o solo de imagen de portada
      continue;
    }

    const normalizedHref = normalizePathKey(fullPath);
    const tocTitle = tocTitles.get(normalizedHref);
    const chapterIndex = chapters.length;
    const chapterTitle =
      tocTitle || extracted.heading || `Capítulo ${chapterIndex + 1}`;

    const charCount = extracted.text.length;
    const wordCount = NarrationEstimator.countWords(extracted.text);
    const estimatedDurationMs = NarrationEstimator.estimateDurationMs(charCount);

    chapters.push({
      chapterIndex,
      title: chapterTitle,
      textContent: extracted.text,
      charCount,
      wordCount,
      estimatedDurationMs,
    });
  }

  if (chapters.length === 0) {
    throw new EpubParseError(
      'No se encontró texto narrable en este archivo EPUB.',
    );
  }

  const totalCharCount = chapters.reduce((acc, ch) => acc + ch.charCount, 0);
  const estimatedDurationMs = NarrationEstimator.estimateDurationMs(totalCharCount);
  const sha256 = await computeSha256Hex(bytes);

  return {
    title: cleanPlainText(title),
    author: cleanPlainText(author),
    language,
    description,
    sha256,
    coverBytes,
    coverMimeType,
    chapters,
    totalCharCount,
    estimatedDurationMs,
  };
}

function extractOpfPath(containerXml: string): string | null {
  const parser = new DOMParser();
  const doc = parser.parseFromString(containerXml, 'application/xml');
  const rootfiles = getElementsByLocalName(doc, 'rootfile');
  for (const rf of rootfiles) {
    const fullPath = rf.getAttribute('full-path');
    if (fullPath) return decodeSafeUri(fullPath);
  }
  return null;
}

async function extractCoverImage(
  zip: JSZip,
  opfDoc: Document,
  opfDir: string,
  manifestMap: Map<string, { id: string; href: string; mediaType: string; properties: string }>,
): Promise<{ coverBytes: Uint8Array | null; coverMimeType: string | null }> {
  let coverItem: { href: string; mediaType: string } | undefined;

  // 1) EPUB 3: properties="cover-image"
  for (const item of manifestMap.values()) {
    if (item.properties.split(/\s+/).includes('cover-image')) {
      coverItem = item;
      break;
    }
  }

  // 2) EPUB 2: <meta name="cover" content="cover-id" />
  if (!coverItem) {
    const metas = getElementsByLocalName(opfDoc, 'meta');
    for (const meta of metas) {
      if ((meta.getAttribute('name') || '').toLowerCase() === 'cover') {
        const coverId = meta.getAttribute('content');
        if (coverId && manifestMap.has(coverId)) {
          coverItem = manifestMap.get(coverId);
          break;
        }
      }
    }
  }

  // 3) Heurística por id o nombre de archivo
  if (!coverItem) {
    for (const item of manifestMap.values()) {
      if (
        item.mediaType.startsWith('image/') &&
        (item.id.toLowerCase().includes('cover') ||
          item.href.toLowerCase().includes('cover') ||
          item.href.toLowerCase().includes('portada'))
      ) {
        coverItem = item;
        break;
      }
    }
  }

  if (!coverItem) return { coverBytes: null, coverMimeType: null };

  const coverPath = resolveRelativePath(opfDir, coverItem.href);
  const entry = findZipEntry(zip, coverPath);
  if (!entry) return { coverBytes: null, coverMimeType: null };

  const bytes = await entry.async('uint8array');
  return {
    coverBytes: bytes,
    coverMimeType: coverItem.mediaType || 'image/jpeg',
  };
}

async function extractTocTitles(
  zip: JSZip,
  opfDoc: Document,
  opfDir: string,
  manifestMap: Map<string, { id: string; href: string; mediaType: string; properties: string }>,
): Promise<Map<string, string>> {
  const titleMap = new Map<string, string>();
  const parser = new DOMParser();

  // 1) EPUB 3 NAV document
  let navItem: { href: string } | undefined;
  for (const item of manifestMap.values()) {
    if (item.properties.split(/\s+/).includes('nav')) {
      navItem = item;
      break;
    }
  }

  if (navItem) {
    const navPath = resolveRelativePath(opfDir, navItem.href);
    const navDir = getDirectoryPrefix(navPath);
    const entry = findZipEntry(zip, navPath);
    if (entry) {
      const navHtml = await entry.async('string');
      const doc = parser.parseFromString(navHtml, 'text/html');
      const anchors = Array.from(doc.querySelectorAll('nav a[href]'));
      for (const a of anchors) {
        const href = a.getAttribute('href');
        const label = cleanPlainText(a.textContent || '');
        if (!href || !label) continue;
        const cleanHref = href.split('#')[0];
        if (!cleanHref) continue;
        const resolved = normalizePathKey(resolveRelativePath(navDir, decodeSafeUri(cleanHref)));
        if (!titleMap.has(resolved)) {
          titleMap.set(resolved, label);
        }
      }
    }
  }

  if (titleMap.size > 0) return titleMap;

  // 2) EPUB 2 NCX document
  const spineEl = getElementsByLocalName(opfDoc, 'spine')[0];
  const tocId = spineEl?.getAttribute('toc');
  let ncxItem = tocId ? manifestMap.get(tocId) : undefined;
  if (!ncxItem) {
    for (const item of manifestMap.values()) {
      if (item.mediaType.includes('ncx') || item.href.toLowerCase().endsWith('.ncx')) {
        ncxItem = item;
        break;
      }
    }
  }

  if (ncxItem) {
    const ncxPath = resolveRelativePath(opfDir, ncxItem.href);
    const ncxDir = getDirectoryPrefix(ncxPath);
    const entry = findZipEntry(zip, ncxPath);
    if (entry) {
      const ncxXml = await entry.async('string');
      const ncxDoc = parser.parseFromString(ncxXml, 'application/xml');
      const navPoints = getElementsByLocalName(ncxDoc, 'navPoint');
      for (const np of navPoints) {
        const label = cleanPlainText(firstTextByLocalName(np, 'text') || '');
        const contentEl = getElementsByLocalName(np, 'content')[0];
        const src = contentEl?.getAttribute('src') || '';
        const cleanSrc = src.split('#')[0];
        if (!cleanSrc || !label) continue;
        const resolved = normalizePathKey(resolveRelativePath(ncxDir, decodeSafeUri(cleanSrc)));
        if (!titleMap.has(resolved)) {
          titleMap.set(resolved, label);
        }
      }
    }
  }

  return titleMap;
}

function extractChapterTextAndHeading(html: string): { text: string; heading: string | null } {
  const parser = new DOMParser();
  const doc = parser.parseFromString(html, 'text/html');

  // Eliminar scripts, estilos y elementos no narrables
  doc.querySelectorAll('script, style, noscript, svg, math, head').forEach((el) => el.remove());

  const headingEl = doc.querySelector('h1, h2, h3, title');
  const heading = headingEl ? cleanPlainText(headingEl.textContent || '') : null;

  const body = doc.body || doc.documentElement;
  if (!body) return { text: '', heading };

  const blockTags = new Set([
    'P',
    'DIV',
    'H1',
    'H2',
    'H3',
    'H4',
    'H5',
    'H6',
    'LI',
    'BLOCKQUOTE',
    'SECTION',
    'ARTICLE',
    'TR',
  ]);

  const paragraphs: string[] = [];
  let inlineBuffer = '';

  const flushInline = () => {
    const cleaned = inlineBuffer.replace(/\s+/g, ' ').trim();
    if (cleaned.length > 0) {
      paragraphs.push(cleaned);
    }
    inlineBuffer = '';
  };

  const walk = (node: Node) => {
    if (node.nodeType === Node.TEXT_NODE) {
      inlineBuffer += node.textContent || '';
      return;
    }
    if (node.nodeType !== Node.ELEMENT_NODE) return;

    const el = node as Element;
    const tag = el.tagName.toUpperCase();
    if (tag === 'BR') {
      inlineBuffer += '\n';
      return;
    }

    const isBlock = blockTags.has(tag);
    if (isBlock) flushInline();
    for (const child of Array.from(el.childNodes)) {
      walk(child);
    }
    if (isBlock) flushInline();
  };

  walk(body);
  flushInline();

  const text = paragraphs.join('\n\n').trim();
  return {
    text,
    heading: heading && heading.length <= 140 ? heading : null,
  };
}

function getElementsByLocalName(root: Document | Element, localName: string): Element[] {
  const lower = localName.toLowerCase();
  const all = Array.from(root.getElementsByTagName('*'));
  return all.filter((el) => {
    const ln = (el.localName || el.tagName).toLowerCase();
    return ln === lower || ln.endsWith(`:${lower}`);
  });
}

function firstTextByLocalName(root: Document | Element, localName: string): string | null {
  const matches = getElementsByLocalName(root, localName);
  for (const el of matches) {
    const txt = (el.textContent || '').trim();
    if (txt) return txt;
  }
  return null;
}

function cleanPlainText(input: string): string {
  return input.replace(/\s+/g, ' ').trim();
}

function getDirectoryPrefix(path: string): string {
  const idx = path.lastIndexOf('/');
  return idx >= 0 ? path.slice(0, idx + 1) : '';
}

function resolveRelativePath(baseDir: string, relativePath: string): string {
  const combined = `${baseDir}${relativePath}`.replace(/\\/g, '/');
  const parts = combined.split('/');
  const resolved: string[] = [];
  for (const part of parts) {
    if (!part || part === '.') continue;
    if (part === '..') {
      resolved.pop();
    } else {
      resolved.push(part);
    }
  }
  return resolved.join('/');
}

function normalizePathKey(path: string): string {
  return path.replace(/^\/+/, '').toLowerCase();
}

function decodeSafeUri(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

function findZipEntry(zip: JSZip, targetPath: string): JSZip.JSZipObject | null {
  const direct = zip.file(targetPath);
  if (direct) return direct;
  const normalizedTarget = normalizePathKey(targetPath);
  for (const key of Object.keys(zip.files)) {
    if (normalizePathKey(key) === normalizedTarget) {
      return zip.files[key];
    }
  }
  return null;
}
