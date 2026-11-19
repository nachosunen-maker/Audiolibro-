import { openDB, type DBSchema, type IDBPDatabase } from 'idb';
import {
  DEFAULT_USER_PREFERENCES,
  type Book,
  type Chapter,
  type ChapterChunk,
  type NarratorVoiceId,
  type UserPreferences,
} from '../domain/models';
import { parseEpubFile } from '../epub/epubParser';

const DB_NAME = 'audiolibreame-web-db';
const DB_VERSION = 1;

interface StoredBinary {
  id: string;
  mimeType: string;
  bytes: Uint8Array;
  updatedAt: number;
}

interface StoredAudioBlob {
  key: string; // e.g. "chunk:<chapterId>:<voiceId>:<chunkIndex>" or "chapter:<chapterId>:<voiceId>"
  bookId: string;
  chapterId: string;
  voiceId: NarratorVoiceId;
  kind: 'chunk' | 'chapter';
  chunkIndex: number;
  mimeType: string;
  bytes: Uint8Array;
  durationMs: number;
  updatedAt: number;
}

interface AudioLibreaMeSchema extends DBSchema {
  books: {
    key: string;
    value: Book;
    indexes: { 'by-sha256': string; 'by-updated': number };
  };
  epubFiles: {
    key: string; // bookId
    value: StoredBinary;
  };
  covers: {
    key: string; // bookId
    value: StoredBinary;
  };
  chapters: {
    key: string; // chapterId
    value: Chapter;
    indexes: { 'by-book': string };
  };
  chunks: {
    key: string; // chunkId
    value: ChapterChunk;
    indexes: { 'by-chapter': string; 'by-book': string };
  };
  audioBlobs: {
    key: string;
    value: StoredAudioBlob;
    indexes: { 'by-book': string; 'by-chapter': string };
  };
  settings: {
    key: string;
    value: UserPreferences;
  };
}

let dbPromise: Promise<IDBPDatabase<AudioLibreaMeSchema>> | null = null;

export function getDb(): Promise<IDBPDatabase<AudioLibreaMeSchema>> {
  if (!dbPromise) {
    dbPromise = openDB<AudioLibreaMeSchema>(DB_NAME, DB_VERSION, {
      upgrade(db) {
        if (!db.objectStoreNames.contains('books')) {
          const bookStore = db.createObjectStore('books', { keyPath: 'id' });
          bookStore.createIndex('by-sha256', 'sha256', { unique: false });
          bookStore.createIndex('by-updated', 'updatedAt', { unique: false });
        }
        if (!db.objectStoreNames.contains('epubFiles')) {
          db.createObjectStore('epubFiles', { keyPath: 'id' });
        }
        if (!db.objectStoreNames.contains('covers')) {
          db.createObjectStore('covers', { keyPath: 'id' });
        }
        if (!db.objectStoreNames.contains('chapters')) {
          const chapterStore = db.createObjectStore('chapters', { keyPath: 'id' });
          chapterStore.createIndex('by-book', 'bookId', { unique: false });
        }
        if (!db.objectStoreNames.contains('chunks')) {
          const chunkStore = db.createObjectStore('chunks', { keyPath: 'id' });
          chunkStore.createIndex('by-chapter', 'chapterId', { unique: false });
          chunkStore.createIndex('by-book', 'bookId', { unique: false });
        }
        if (!db.objectStoreNames.contains('audioBlobs')) {
          const audioStore = db.createObjectStore('audioBlobs', { keyPath: 'key' });
          audioStore.createIndex('by-book', 'bookId', { unique: false });
          audioStore.createIndex('by-chapter', 'chapterId', { unique: false });
        }
        if (!db.objectStoreNames.contains('settings')) {
          db.createObjectStore('settings');
        }
      },
    });
  }
  return dbPromise;
}

/** Reinicia el handle en caché (útil para tests con fake-indexeddb). */
export async function resetDbConnectionForTests(): Promise<void> {
  if (dbPromise) {
    const db = await dbPromise;
    db.close();
    dbPromise = null;
  }
}

export async function requestPersistentStorage(): Promise<{
  supported: boolean;
  persisted: boolean;
}> {
  if (typeof navigator === 'undefined' || !navigator.storage?.persist) {
    return { supported: false, persisted: false };
  }
  try {
    const already = await navigator.storage.persisted();
    if (already) return { supported: true, persisted: true };
    const granted = await navigator.storage.persist();
    return { supported: true, persisted: granted };
  } catch {
    return { supported: true, persisted: false };
  }
}

export interface StorageUsageSummary {
  quotaBytes: number | null;
  usageBytes: number | null;
  bookCount: number;
  cachedAudioBytes: number;
}

export async function getStorageUsageSummary(): Promise<StorageUsageSummary> {
  const db = await getDb();
  const books = await db.getAll('books');
  const audioBlobs = await db.getAll('audioBlobs');
  const cachedAudioBytes = audioBlobs.reduce((acc, a) => acc + a.bytes.byteLength, 0);

  let quotaBytes: number | null = null;
  let usageBytes: number | null = null;
  if (typeof navigator !== 'undefined' && navigator.storage?.estimate) {
    try {
      const est = await navigator.storage.estimate();
      quotaBytes = est.quota ?? null;
      usageBytes = est.usage ?? null;
    } catch {
      // Ignorar si el navegador restringe estimate()
    }
  }

  return {
    quotaBytes,
    usageBytes,
    bookCount: books.length,
    cachedAudioBytes,
  };
}

export function makeChunkAudioKey(
  chapterId: string,
  voiceId: NarratorVoiceId,
  chunkIndex: number,
): string {
  return `chunk:${chapterId}:${voiceId}:${chunkIndex}`;
}

export function makeChapterAudioKey(
  chapterId: string,
  voiceId: NarratorVoiceId,
): string {
  return `chapter:${chapterId}:${voiceId}`;
}

export interface ImportBookResult {
  book: Book;
  isDuplicate: boolean;
}

export async function importEpubToLibrary(
  fileBuffer: ArrayBuffer | Uint8Array,
  fileName: string,
  preferredVoice: NarratorVoiceId = 'MALE',
): Promise<ImportBookResult> {
  const bytes = fileBuffer instanceof Uint8Array ? fileBuffer : new Uint8Array(fileBuffer);
  const parsed = await parseEpubFile(bytes, fileName);
  const db = await getDb();

  // Comprobar duplicado por SHA-256
  const existing = await db.getFromIndex('books', 'by-sha256', parsed.sha256);
  if (existing) {
    return { book: existing, isDuplicate: true };
  }

  const now = Date.now();
  const bookId = `book_${now}_${Math.random().toString(36).slice(2, 8)}`;

  const book: Book = {
    id: bookId,
    title: parsed.title,
    author: parsed.author,
    language: parsed.language,
    description: parsed.description,
    sha256: parsed.sha256,
    fileName,
    fileSizeBytes: bytes.byteLength,
    chapterCount: parsed.chapters.length,
    totalCharCount: parsed.totalCharCount,
    estimatedDurationMs: parsed.estimatedDurationMs,
    hasCover: Boolean(parsed.coverBytes && parsed.coverBytes.byteLength > 0),
    coverMimeType: parsed.coverMimeType ?? undefined,
    preferredVoice,
    lastChapterIndex: 0,
    lastPositionMs: 0,
    playbackSpeed: 1.0,
    status: 'READY',
    createdAt: now,
    updatedAt: now,
  };

  const chapters: Chapter[] = parsed.chapters.map((ch) => ({
    id: `${bookId}_ch_${String(ch.chapterIndex).padStart(4, '0')}`,
    bookId,
    chapterIndex: ch.chapterIndex,
    title: ch.title,
    textContent: ch.textContent,
    charCount: ch.charCount,
    wordCount: ch.wordCount,
    estimatedDurationMs: ch.estimatedDurationMs,
    isSynthesized: false,
    synthesizedDurationMs: 0,
    audioSizeBytes: 0,
  }));

  const tx = db.transaction(['books', 'epubFiles', 'covers', 'chapters'], 'readwrite');
  await tx.objectStore('books').put(book);
  await tx.objectStore('epubFiles').put({
    id: bookId,
    mimeType: 'application/epub+zip',
    bytes,
    updatedAt: now,
  });
  if (parsed.coverBytes && parsed.coverBytes.byteLength > 0) {
    await tx.objectStore('covers').put({
      id: bookId,
      mimeType: parsed.coverMimeType || 'image/jpeg',
      bytes: parsed.coverBytes,
      updatedAt: now,
    });
  }
  const chStore = tx.objectStore('chapters');
  for (const ch of chapters) {
    await chStore.put(ch);
  }
  await tx.done;

  return { book, isDuplicate: false };
}

export async function getAllBooks(): Promise<Book[]> {
  const db = await getDb();
  const books = await db.getAll('books');
  return books.sort((a, b) => b.updatedAt - a.updatedAt);
}

export async function getBookById(bookId: string): Promise<Book | undefined> {
  const db = await getDb();
  return db.get('books', bookId);
}

export async function getBookCoverBlob(bookId: string): Promise<Blob | null> {
  const db = await getDb();
  const record = await db.get('covers', bookId);
  if (!record) return null;
  const copy = new Uint8Array(record.bytes.byteLength);
  copy.set(record.bytes);
  return new Blob([copy.buffer], { type: record.mimeType || 'image/jpeg' });
}

export async function getBookChapters(bookId: string): Promise<Chapter[]> {
  const db = await getDb();
  const chapters = await db.getAllFromIndex('chapters', 'by-book', bookId);
  return chapters.sort((a, b) => a.chapterIndex - b.chapterIndex);
}

export async function getChapterById(chapterId: string): Promise<Chapter | undefined> {
  const db = await getDb();
  return db.get('chapters', chapterId);
}

export async function updateBookProgress(
  bookId: string,
  chapterIndex: number,
  positionMs: number,
  playbackSpeed?: number,
): Promise<void> {
  const db = await getDb();
  const book = await db.get('books', bookId);
  if (!book) return;
  book.lastChapterIndex = Math.max(0, chapterIndex);
  book.lastPositionMs = Math.max(0, Math.floor(positionMs));
  if (typeof playbackSpeed === 'number') {
    book.playbackSpeed = playbackSpeed;
  }
  book.updatedAt = Date.now();
  await db.put('books', book);
}

export async function updateBookPreferredVoice(
  bookId: string,
  voiceId: NarratorVoiceId,
): Promise<void> {
  const db = await getDb();
  const book = await db.get('books', bookId);
  if (!book) return;
  book.preferredVoice = voiceId;
  book.updatedAt = Date.now();
  await db.put('books', book);
}

export async function saveChapterChunks(chunks: ChapterChunk[]): Promise<void> {
  const db = await getDb();
  const tx = db.transaction('chunks', 'readwrite');
  for (const chunk of chunks) {
    await tx.store.put(chunk);
  }
  await tx.done;
}

export async function getChapterChunks(
  chapterId: string,
  voiceId: NarratorVoiceId,
): Promise<ChapterChunk[]> {
  const db = await getDb();
  const all = await db.getAllFromIndex('chunks', 'by-chapter', chapterId);
  return all
    .filter((c) => c.voiceId === voiceId)
    .sort((a, b) => a.chunkIndex - b.chunkIndex);
}

export async function saveAudioBlob(record: StoredAudioBlob): Promise<void> {
  const db = await getDb();
  await db.put('audioBlobs', record);
}

export async function getAudioBlob(key: string): Promise<StoredAudioBlob | undefined> {
  const db = await getDb();
  return db.get('audioBlobs', key);
}

export async function markChapterSynthesized(
  chapterId: string,
  voiceId: NarratorVoiceId,
  durationMs: number,
  audioSizeBytes: number,
): Promise<void> {
  const db = await getDb();
  const ch = await db.get('chapters', chapterId);
  if (!ch) return;
  ch.isSynthesized = true;
  ch.synthesizedVoice = voiceId;
  ch.synthesizedDurationMs = durationMs;
  ch.audioSizeBytes = audioSizeBytes;
  await db.put('chapters', ch);
}

export async function clearBookAudioCache(bookId: string): Promise<void> {
  const db = await getDb();
  const tx = db.transaction(['audioBlobs', 'chunks', 'chapters'], 'readwrite');

  const audioKeys = await tx.objectStore('audioBlobs').index('by-book').getAllKeys(bookId);
  for (const key of audioKeys) {
    await tx.objectStore('audioBlobs').delete(key);
  }

  const chunkKeys = await tx.objectStore('chunks').index('by-book').getAllKeys(bookId);
  for (const key of chunkKeys) {
    await tx.objectStore('chunks').delete(key);
  }

  const chapters = await tx.objectStore('chapters').index('by-book').getAll(bookId);
  for (const ch of chapters) {
    ch.isSynthesized = false;
    ch.synthesizedVoice = undefined;
    ch.synthesizedDurationMs = 0;
    ch.audioSizeBytes = 0;
    await tx.objectStore('chapters').put(ch);
  }

  await tx.done;
}

export async function deleteBookCompletely(bookId: string): Promise<void> {
  const db = await getDb();
  const tx = db.transaction(
    ['books', 'epubFiles', 'covers', 'chapters', 'chunks', 'audioBlobs'],
    'readwrite',
  );

  await tx.objectStore('books').delete(bookId);
  await tx.objectStore('epubFiles').delete(bookId);
  await tx.objectStore('covers').delete(bookId);

  const chapterKeys = await tx.objectStore('chapters').index('by-book').getAllKeys(bookId);
  for (const key of chapterKeys) {
    await tx.objectStore('chapters').delete(key);
  }

  const chunkKeys = await tx.objectStore('chunks').index('by-book').getAllKeys(bookId);
  for (const key of chunkKeys) {
    await tx.objectStore('chunks').delete(key);
  }

  const audioKeys = await tx.objectStore('audioBlobs').index('by-book').getAllKeys(bookId);
  for (const key of audioKeys) {
    await tx.objectStore('audioBlobs').delete(key);
  }

  await tx.done;
}

export async function getUserPreferences(): Promise<UserPreferences> {
  const db = await getDb();
  const stored = await db.get('settings', 'preferences');
  return stored ? { ...DEFAULT_USER_PREFERENCES, ...stored } : { ...DEFAULT_USER_PREFERENCES };
}

export async function saveUserPreferences(prefs: UserPreferences): Promise<void> {
  const db = await getDb();
  await db.put('settings', prefs, 'preferences');
}

export interface LibraryBackupPayload {
  version: 1;
  exportedAt: number;
  preferences: UserPreferences;
  books: Array<{
    book: Book;
    epubBase64: string;
  }>;
}

export async function exportLibraryBackup(): Promise<Blob> {
  const db = await getDb();
  const books = await getAllBooks();
  const preferences = await getUserPreferences();
  const payload: LibraryBackupPayload = {
    version: 1,
    exportedAt: Date.now(),
    preferences,
    books: [],
  };

  for (const book of books) {
    const epub = await db.get('epubFiles', book.id);
    if (!epub) continue;
    payload.books.push({
      book,
      epubBase64: uint8ToBase64(epub.bytes),
    });
  }

  return new Blob([JSON.stringify(payload)], { type: 'application/json' });
}

export async function restoreLibraryBackup(
  jsonText: string,
): Promise<{ imported: number; skippedDuplicates: number }> {
  const parsed = JSON.parse(jsonText) as LibraryBackupPayload;
  if (!parsed || parsed.version !== 1 || !Array.isArray(parsed.books)) {
    throw new Error('Archivo de copia de seguridad no válido.');
  }

  if (parsed.preferences) {
    await saveUserPreferences({ ...DEFAULT_USER_PREFERENCES, ...parsed.preferences });
  }

  let imported = 0;
  let skippedDuplicates = 0;

  for (const item of parsed.books) {
    const epubBytes = base64ToUint8(item.epubBase64);
    const res = await importEpubToLibrary(
      epubBytes,
      item.book.fileName || `${item.book.title}.epub`,
      item.book.preferredVoice || 'MALE',
    );
    if (res.isDuplicate) {
      skippedDuplicates++;
    } else {
      imported++;
      await updateBookProgress(
        res.book.id,
        item.book.lastChapterIndex || 0,
        item.book.lastPositionMs || 0,
        item.book.playbackSpeed || 1.0,
      );
    }
  }

  return { imported, skippedDuplicates };
}

function uint8ToBase64(bytes: Uint8Array): string {
  let binary = '';
  const chunkSize = 0x8000;
  for (let i = 0; i < bytes.byteLength; i += chunkSize) {
    const sub = bytes.subarray(i, i + chunkSize);
    binary += String.fromCharCode(...sub);
  }
  return btoa(binary);
}

function base64ToUint8(base64: string): Uint8Array {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}
