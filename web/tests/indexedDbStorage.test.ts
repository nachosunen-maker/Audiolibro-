import 'fake-indexeddb/auto';
import JSZip from 'jszip';
import { beforeEach, describe, expect, it } from 'vitest';
import {
  clearBookAudioCache,
  deleteBookCompletely,
  exportLibraryBackup,
  getAllBooks,
  getBookById,
  getBookChapters,
  importEpubToLibrary,
  resetDbConnectionForTests,
  restoreLibraryBackup,
  updateBookPreferredVoice,
  updateBookProgress,
} from '../src/storage/db';

async function createMinimalEpub(title: string, body: string): Promise<Uint8Array> {
  const zip = new JSZip();
  zip.file(
    'META-INF/container.xml',
    `<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
      <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles>
    </container>`,
  );
  zip.file(
    'content.opf',
    `<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:title>${title}</dc:title>
        <dc:creator>Autor Local</dc:creator>
        <dc:language>es</dc:language>
      </metadata>
      <manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest>
      <spine><itemref idref="c1"/></spine>
    </package>`,
  );
  zip.file('c1.xhtml', `<html><body><h1>Inicio</h1><p>${body}</p></body></html>`);
  return zip.generateAsync({ type: 'uint8array' });
}

describe('IndexedDB persistence & backup/restore', () => {
  beforeEach(async () => {
    await resetDbConnectionForTests();
    const books = await getAllBooks();
    for (const b of books) {
      await deleteBookCompletely(b.id);
    }
  });

  it('imports EPUB, deduplicates by SHA-256, persists progress and voice, and restores backup', async () => {
    const epub = await createMinimalEpub(
      'Novela de Prueba',
      'Este es un texto suficientemente largo para ser aceptado como capítulo narrable en español.',
    );

    const first = await importEpubToLibrary(epub, 'novela.epub', 'MALE');
    expect(first.isDuplicate).toBe(false);
    expect(first.book.title).toBe('Novela de Prueba');

    const second = await importEpubToLibrary(epub, 'novela-copia.epub', 'MALE');
    expect(second.isDuplicate).toBe(true);
    expect(second.book.id).toBe(first.book.id);

    await updateBookPreferredVoice(first.book.id, 'FEMALE');
    await updateBookProgress(first.book.id, 0, 42500, 1.25);

    const reloaded = await getBookById(first.book.id);
    expect(reloaded?.preferredVoice).toBe('FEMALE');
    expect(reloaded?.lastPositionMs).toBe(42500);
    expect(reloaded?.playbackSpeed).toBe(1.25);

    const chapters = await getBookChapters(first.book.id);
    expect(chapters).toHaveLength(1);

    // Exportar copia de seguridad, borrar todo y restaurar
    const backupBlob = await exportLibraryBackup();
    const backupJson = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result || ''));
      reader.onerror = () => reject(reader.error);
      reader.readAsText(backupBlob);
    });

    await clearBookAudioCache(first.book.id);
    await deleteBookCompletely(first.book.id);
    expect(await getAllBooks()).toHaveLength(0);

    const restored = await restoreLibraryBackup(backupJson);
    expect(restored.imported).toBe(1);
    expect(restored.skippedDuplicates).toBe(0);

    const restoredBooks = await getAllBooks();
    expect(restoredBooks).toHaveLength(1);
    expect(restoredBooks[0].title).toBe('Novela de Prueba');
    expect(restoredBooks[0].lastPositionMs).toBe(42500);
    expect(restoredBooks[0].preferredVoice).toBe('FEMALE');
  });
});
