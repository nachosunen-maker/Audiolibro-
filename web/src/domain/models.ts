/**
 * Modelos de dominio de AudioLibreaMe Web (PWA).
 * Réplica fiel del modelo Android (AudiobookModels.kt).
 */

export type NarratorVoiceId = 'MALE' | 'FEMALE';

export interface NarratorVoice {
  id: NarratorVoiceId;
  displayName: string;
  shortLabel: string;
  speakerId: number;
  language: string;
  modelId: string;
  description: string;
}

export const NARRATOR_VOICES: Record<NarratorVoiceId, NarratorVoice> = {
  MALE: {
    id: 'MALE',
    displayName: 'Voz masculina (es-ES)',
    shortLabel: 'Masculina',
    speakerId: 0,
    language: 'es-ES',
    modelId: 'es_ES-sharvard-medium',
    description: 'Voz masculina en español de España (Piper VITS Sharvard, locutor 0)',
  },
  FEMALE: {
    id: 'FEMALE',
    displayName: 'Voz femenina (es-ES)',
    shortLabel: 'Femenina',
    speakerId: 1,
    language: 'es-ES',
    modelId: 'es_ES-sharvard-medium',
    description: 'Voz femenina en español de España (Piper VITS Sharvard, locutor 1)',
  },
};

export const NARRATOR_VOICE_LIST: NarratorVoice[] = [
  NARRATOR_VOICES.MALE,
  NARRATOR_VOICES.FEMALE,
];

export const PLAYBACK_SPEEDS = [0.85, 1.0, 1.25, 1.5] as const;

export type BookStatus = 'IMPORTED' | 'READY' | 'ERROR';

export interface Book {
  id: string;
  title: string;
  author: string;
  language: string;
  description: string;
  sha256: string;
  fileName: string;
  fileSizeBytes: number;
  chapterCount: number;
  totalCharCount: number;
  estimatedDurationMs: number;
  hasCover: boolean;
  coverMimeType?: string;
  preferredVoice: NarratorVoiceId;
  lastChapterIndex: number;
  lastPositionMs: number;
  playbackSpeed: number;
  status: BookStatus;
  createdAt: number;
  updatedAt: number;
}

export interface Chapter {
  id: string;
  bookId: string;
  chapterIndex: number;
  title: string;
  textContent: string;
  charCount: number;
  wordCount: number;
  estimatedDurationMs: number;
  isSynthesized: boolean;
  synthesizedVoice?: NarratorVoiceId;
  synthesizedDurationMs: number;
  audioSizeBytes: number;
}

export type ChunkStatus = 'PENDING' | 'SYNTHESIZING' | 'READY' | 'FAILED';

export interface ChapterChunk {
  id: string;
  bookId: string;
  chapterId: string;
  chapterIndex: number;
  chunkIndex: number;
  voiceId: NarratorVoiceId;
  text: string;
  charCount: number;
  status: ChunkStatus;
  durationMs: number;
  audioKey: string;
  updatedAt: number;
}

export type ProgressiveChapterPhase =
  | 'IDLE'
  | 'DOWNLOADING_MODEL'
  | 'PREPARING'
  | 'SYNTHESIZING'
  | 'READY_TO_PLAY'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'ERROR';

export interface ProgressiveChapterState {
  bookId: string | null;
  bookTitle: string;
  chapterId: string | null;
  chapterIndex: number;
  chapterTitle: string;
  voiceId: NarratorVoiceId;
  phase: ProgressiveChapterPhase;
  totalChunks: number;
  completedChunks: number;
  totalChapterChars: number;
  synthesizedChapterChars: number;
  currentChunkIndex: number;
  canStartPlayback: boolean;
  modelDownloadProgress: number; // 0..1
  modelDownloadMessage: string | null;
  statusMessage: string;
  errorMessage: string | null;
  realtimeFactor: number | null;
}

export interface ActiveWindowTelemetry {
  activeWindowStartChunk: number;
  activeWindowEndChunk: number;
  bufferedAheadMs: number;
  activeObjectUrlCount: number;
  targetWindowSeconds: number;
  memoryMode: 'CONSTRAINED' | 'STANDARD';
  releasedChunksCount: number;
}

export interface PlaybackUiState {
  bookId: string | null;
  bookTitle: string;
  bookAuthor: string;
  chapterId: string | null;
  chapterIndex: number;
  chapterTitle: string;
  totalChapters: number;
  voiceId: NarratorVoiceId;
  isPlaying: boolean;
  isBuffering: boolean;
  isProgressiveSession: boolean;
  currentChunkIndex: number;
  totalChunksInChapter: number;
  positionMs: number;
  durationMs: number;
  playbackSpeed: number;
  windowTelemetry: ActiveWindowTelemetry;
  errorMessage: string | null;
}

export interface UserPreferences {
  themeMode: 'system' | 'light' | 'dark';
  readerFontSizeSp: number;
  defaultVoice: NarratorVoiceId;
  defaultPlaybackSpeed: number;
}

export const DEFAULT_USER_PREFERENCES: UserPreferences = {
  themeMode: 'system',
  readerFontSizeSp: 18,
  defaultVoice: 'MALE',
  defaultPlaybackSpeed: 1.0,
};

export const NarrationEstimator = {
  /** Velocidad media de narración Piper es_ES (~14.5 caracteres/segundo). */
  CHARS_PER_SECOND: 14.5,

  estimateDurationMs(charCount: number): number {
    if (charCount <= 0) return 0;
    return Math.round((charCount / this.CHARS_PER_SECOND) * 1000);
  },

  countWords(text: string): number {
    const trimmed = text.trim();
    if (!trimmed) return 0;
    return trimmed.split(/\s+/).length;
  },
};

export function formatIntegerEs(value: number): string {
  return new Intl.NumberFormat('es-ES').format(Math.round(value));
}

export function formatDurationMs(ms: number): string {
  const safeMs = Math.max(0, Math.floor(ms));
  const totalSeconds = Math.floor(safeMs / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) {
    return `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
  }
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

export function formatHumanDurationMs(ms: number): string {
  const totalMinutes = Math.max(1, Math.round(ms / 60000));
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours > 0) {
    return `${hours} h ${minutes} min`;
  }
  return `${minutes} min`;
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb.toFixed(1)} KB`;
  const mb = kb / 1024;
  return `${mb.toFixed(1)} MB`;
}
