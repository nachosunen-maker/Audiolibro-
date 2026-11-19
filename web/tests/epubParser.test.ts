import JSZip from 'jszip';
import { describe, expect, it } from 'vitest';
import { EpubParseError, parseEpubFile } from '../src/epub/epubParser';

async function buildSampleEpub(options?: { withDrm?: boolean }): Promise<Uint8Array> {
  const zip = new JSZip();
  zip.file('mimetype', 'application/epub+zip', { compression: 'STORE' });
  zip.file(
    'META-INF/container.xml',
    `<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>`,
  );

  if (options?.withDrm) {
    zip.file(
      'META-INF/encryption.xml',
      `<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
        <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#"/>
      </encryption>`,
    );
  }

  zip.file(
    'OEBPS/content.opf',
    `<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>El Quijote de Prueba</dc:title>
    <dc:creator>Miguel de Cervantes</dc:creator>
    <dc:language>es</dc:language>
    <dc:description>Edición de prueba local.</dc:description>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="cover" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
</package>`,
  );

  zip.file(
    'OEBPS/nav.xhtml',
    `<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<body>
  <nav>
    <ol>
      <li><a href="ch1.xhtml">Capítulo I: Del hidalgo</a></li>
      <li><a href="ch2.xhtml">Capítulo II: Primera salida</a></li>
    </ol>
  </nav>
</body>
</html>`,
  );

  zip.file('OEBPS/cover.jpg', new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10]));

  zip.file(
    'OEBPS/ch1.xhtml',
    `<html xmlns="http://www.w3.org/1999/xhtml"><body>
      <h1>Capítulo I</h1>
      <p>En un lugar de la Mancha, de cuyo nombre no quiero acordarme, no ha mucho tiempo que vivía un hidalgo.</p>
    </body></html>`,
  );

  zip.file(
    'OEBPS/ch2.xhtml',
    `<html xmlns="http://www.w3.org/1999/xhtml"><body>
      <h1>Capítulo II</h1>
      <p>Hechas, pues, estas prevenciones, no quiso aguardar más tiempo a poner en efecto su pensamiento.</p>
    </body></html>`,
  );

  return zip.generateAsync({ type: 'uint8array' });
}

describe('EPUB local parser', () => {
  it('parses metadata, cover, TOC titles, chapters, and deterministic SHA-256', async () => {
    const epubBytes = await buildSampleEpub();
    const parsed = await parseEpubFile(epubBytes, 'quijote.epub');

    expect(parsed.title).toBe('El Quijote de Prueba');
    expect(parsed.author).toBe('Miguel de Cervantes');
    expect(parsed.language).toBe('es');
    expect(parsed.coverBytes).not.toBeNull();
    expect(parsed.coverBytes?.byteLength).toBe(6);
    expect(parsed.chapters).toHaveLength(2);
    expect(parsed.chapters[0].title).toBe('Capítulo I: Del hidalgo');
    expect(parsed.chapters[1].title).toBe('Capítulo II: Primera salida');
    expect(parsed.chapters[0].textContent).toContain('En un lugar de la Mancha');
    expect(parsed.sha256.length).toBeGreaterThanOrEqual(16);

    const parsedAgain = await parseEpubFile(epubBytes, 'quijote.epub');
    expect(parsedAgain.sha256).toBe(parsed.sha256);
  });

  it('rejects DRM-protected EPUB files clearly', async () => {
    const drmEpub = await buildSampleEpub({ withDrm: true });
    await expect(parseEpubFile(drmEpub, 'drm.epub')).rejects.toThrow(EpubParseError);
  });
});
