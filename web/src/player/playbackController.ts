import {
  type Book,
  type Chapter,
  type ChapterChunk,
  type NarratorVoiceId,
  type PlaybackUiState,
} from '../domain/models';
import {
  getAudioBlob,
  getBookChapters,
  getBookCoverBlob,
  getChapterChunks,
  updateBookProgress,
} from '../storage/db';
import { chapterOrchestrator } from '../tts/chapterOrchestrator';
import { SlidingWindowAudioCache } from './slidingWindowCache';

const INITIAL_PLAYBACK_STATE: PlaybackUiState = {
  bookId: null,
  bookTitle: '',
  bookAuthor: '',
  chapterId: null,
  chapterIndex: 0,
  chapterTitle: '',
  totalChapters: 0,
  voiceId: 'MALE',
  isPlaying: false,
  isBuffering: false,
  isProgressiveSession: false,
  currentChunkIndex: 0,
  totalChunksInChapter: 0,
  positionMs: 0,
  durationMs: 0,
  playbackSpeed: 1.0,
  windowTelemetry: {
    activeWindowStartChunk: 0,
    activeWindowEndChunk: 0,
    bufferedAheadMs: 0,
    activeObjectUrlCount: 0,
    targetWindowSeconds: 30,
    memoryMode: 'STANDARD',
    releasedChunksCount: 0,
  },
  errorMessage: null,
};

/**
 * Controlador de reproducción secuencial con ventana deslizante de memoria,
 * recuperación transparente desde IndexedDB en saltos hacia atrás,
 * avance automático de capítulo bajo demanda e integración con Media Session API.
 */
export class AudiobookPlaybackController {
  private audio: HTMLAudioElement | null = null;
  private state: PlaybackUiState = { ...INITIAL_PLAYBACK_STATE };
  private listeners = new Set<(state: PlaybackUiState) => void>();
  private cache = new SlidingWindowAudioCache();
  private activeBook: Book | null = null;
  private activeChapters: Chapter[] = [];
  private activeChunks: ChapterChunk[] = [];
  private coverObjectUrl: string | null = null;
  private waitingForChunkIndex: number | null = null;
  private lastSavedTimestamp = 0;

  constructor() {
    if (typeof window !== 'undefined' && typeof Audio !== 'undefined') {
      const audio = new Audio();
      audio.preload = 'auto';
      audio.addEventListener('timeupdate', () => this.handleTimeUpdate());
      audio.addEventListener('ended', () => void this.handleChunkEnded());
      audio.addEventListener('play', () => this.updatePlayState(true));
      audio.addEventListener('pause', () => this.updatePlayState(false));
      audio.addEventListener('waiting', () =>
        this.emit({ ...this.state, isBuffering: true }),
      );
      audio.addEventListener('playing', () =>
        this.emit({ ...this.state, isBuffering: false, isPlaying: true }),
      );
      audio.addEventListener('error', () => {
        this.emit({
          ...this.state,
          isPlaying: false,
          isBuffering: false,
          errorMessage: 'No se pudo reproducir el fragmento de audio actual.',
        });
      });
      this.audio = audio;
    }

    // Reaccionar cuando el orquestador termine un fragmento esperado
    chapterOrchestrator.onChunkReady((chapterId, chunkIndex) => {
      if (this.state.chapterId !== chapterId) return;
      void this.refreshChunksFromDb().then(async () => {
        if (this.waitingForChunkIndex === chunkIndex) {
          this.waitingForChunkIndex = null;
          await this.playChunkAt(chunkIndex, 0, true);
        } else {
          await this.preloadForwardWindow(this.state.currentChunkIndex);
        }
      });
    });
  }

  getState(): PlaybackUiState {
    return this.state;
  }

  subscribe(listener: (state: PlaybackUiState) => void): () => void {
    this.listeners.add(listener);
    listener(this.state);
    return () => this.listeners.delete(listener);
  }

  private emit(next: PlaybackUiState): void {
    this.state = next;
    for (const listener of this.listeners) {
      listener(next);
    }
  }

  async openChapterSession(
    book: Book,
    chapter: Chapter,
    voiceId: NarratorVoiceId,
    options?: { startPositionMs?: number; autoPlay?: boolean },
  ): Promise<void> {
    this.cache.clearAll();
    this.waitingForChunkIndex = null;
    this.activeBook = book;
    this.activeChapters = await getBookChapters(book.id);
    this.activeChunks = await getChapterChunks(chapter.id, voiceId);

    // Actualizar portada para Media Session
    if (this.coverObjectUrl) {
      URL.revokeObjectURL(this.coverObjectUrl);
      this.coverObjectUrl = null;
    }
    const coverBlob = await getBookCoverBlob(book.id);
    if (coverBlob) {
      this.coverObjectUrl = URL.createObjectURL(coverBlob);
    }

    const totalDurationMs = this.computeChapterDurationMs();
    const speed = book.playbackSpeed || 1.0;
    if (this.audio) {
      this.audio.playbackRate = speed;
    }

    this.emit({
      bookId: book.id,
      bookTitle: book.title,
      bookAuthor: book.author,
      chapterId: chapter.id,
      chapterIndex: chapter.chapterIndex,
      chapterTitle: chapter.title,
      totalChapters: this.activeChapters.length,
      voiceId,
      isPlaying: false,
      isBuffering: false,
      isProgressiveSession: true,
      currentChunkIndex: 0,
      totalChunksInChapter: this.activeChunks.length,
      positionMs: options?.startPositionMs ?? 0,
      durationMs: totalDurationMs,
      playbackSpeed: speed,
      windowTelemetry: this.cache.getTelemetry(0),
      errorMessage: null,
    });

    this.updateMediaSessionMetadata();

    const targetPos = options?.startPositionMs ?? 0;
    const { chunkIndex, offsetMs } = this.locateChunkForPosition(targetPos);
    await this.playChunkAt(chunkIndex, offsetMs, options?.autoPlay ?? true);
  }

  async togglePlayPause(): Promise<void> {
    if (!this.audio || !this.state.chapterId) return;
    if (this.state.isPlaying) {
      this.audio.pause();
      await this.persistCurrentProgress(true);
    } else {
      if (!this.audio.src) {
        await this.playChunkAt(this.state.currentChunkIndex, 0, true);
      } else {
        try {
          await this.audio.play();
        } catch {
          this.emit({
            ...this.state,
            errorMessage: 'Pulsa de nuevo para iniciar la reproducción de audio.',
          });
        }
      }
    }
  }

  pause(): void {
    if (this.audio && !this.audio.paused) {
      this.audio.pause();
      void this.persistCurrentProgress(true);
    }
  }

  async setPlaybackSpeed(speed: number): Promise<void> {
    if (this.audio) {
      this.audio.playbackRate = speed;
    }
    this.emit({ ...this.state, playbackSpeed: speed });
    if (this.activeBook) {
      this.activeBook.playbackSpeed = speed;
      await updateBookProgress(
        this.activeBook.id,
        this.state.chapterIndex,
        this.state.positionMs,
        speed,
      );
    }
  }

  /**
   * Busca a cualquier posición del capítulo. Si el fragmento destino fue liberado de RAM,
   * se recupera directamente desde IndexedDB sin resintetizar.
   */
  async seekToMs(targetMs: number): Promise<void> {
    if (!this.state.chapterId || this.activeChunks.length === 0) return;
    const clamped = Math.max(0, Math.min(targetMs, this.state.durationMs));
    const { chunkIndex, offsetMs } = this.locateChunkForPosition(clamped);
    const shouldResume = this.state.isPlaying;
    await this.playChunkAt(chunkIndex, offsetMs, shouldResume);
    await this.persistCurrentProgress(true);
  }

  async skipByMs(deltaMs: number): Promise<void> {
    await this.seekToMs(this.state.positionMs + deltaMs);
  }

  async goToChapterOffset(delta: -1 | 1): Promise<void> {
    if (!this.activeBook || this.activeChapters.length === 0) return;
    const targetIdx = this.state.chapterIndex + delta;
    if (targetIdx < 0 || targetIdx >= this.activeChapters.length) return;
    const nextChapter = this.activeChapters[targetIdx];
    const voiceId = this.state.voiceId;

    // Lanzar síntesis bajo demanda del capítulo destino (nunca todo el libro)
    void chapterOrchestrator.startChapterSynthesis(this.activeBook, nextChapter, voiceId);
    await this.openChapterSession(this.activeBook, nextChapter, voiceId, {
      startPositionMs: 0,
      autoPlay: true,
    });
  }

  stopAndRelease(): void {
    if (this.audio) {
      this.audio.pause();
      this.audio.removeAttribute('src');
      this.audio.load();
    }
    this.cache.clearAll();
    if (this.coverObjectUrl) {
      URL.revokeObjectURL(this.coverObjectUrl);
      this.coverObjectUrl = null;
    }
    this.waitingForChunkIndex = null;
    this.emit({ ...INITIAL_PLAYBACK_STATE });
  }

  private async refreshChunksFromDb(): Promise<void> {
    if (!this.state.chapterId) return;
    this.activeChunks = await getChapterChunks(this.state.chapterId, this.state.voiceId);
    this.emit({
      ...this.state,
      totalChunksInChapter: this.activeChunks.length,
      durationMs: this.computeChapterDurationMs(),
    });
  }

  private async playChunkAt(
    chunkIndex: number,
    offsetMs: number,
    autoPlay: boolean,
  ): Promise<void> {
    if (!this.audio || !this.state.chapterId) return;

    await this.refreshChunksFromDb();
    const chunk = this.activeChunks[chunkIndex];
    if (!chunk) return;

    // Liberar inmediatamente fragmentos ya reproducidos (< chunkIndex)
    this.cache.evictOutsideWindow(chunkIndex);

    let entry = this.cache.get(chunkIndex);
    if (!entry) {
      // Recuperar WAV desde IndexedDB (tanto en avance normal como al retroceder)
      const stored = await getAudioBlob(chunk.audioKey);
      if (!stored) {
        // El fragmento aún se está sintetizando progresivamente
        this.waitingForChunkIndex = chunkIndex;
        this.emit({
          ...this.state,
          currentChunkIndex: chunkIndex,
          isBuffering: true,
          isPlaying: false,
          windowTelemetry: this.cache.getTelemetry(chunkIndex),
        });
        return;
      }
      entry = this.cache.putChunk(chunkIndex, stored.bytes, stored.durationMs, chunkIndex);
    }

    this.waitingForChunkIndex = null;
    this.audio.src = entry.objectUrl;
    this.audio.playbackRate = this.state.playbackSpeed;
    this.audio.currentTime = Math.max(0, offsetMs / 1000);

    const chapterOffsetMs = this.getChunkStartOffsetMs(chunkIndex);
    this.emit({
      ...this.state,
      currentChunkIndex: chunkIndex,
      positionMs: chapterOffsetMs + offsetMs,
      durationMs: this.computeChapterDurationMs(),
      isBuffering: false,
      errorMessage: null,
      windowTelemetry: this.cache.getTelemetry(chunkIndex),
    });

    if (autoPlay) {
      try {
        await this.audio.play();
      } catch {
        this.emit({ ...this.state, isPlaying: false });
      }
    }

    // Precargar solo dentro de la ventana deslizante forward (20s / 30s)
    await this.preloadForwardWindow(chunkIndex);
  }

  private async preloadForwardWindow(currentChunkIndex: number): Promise<void> {
    let nextIdx = currentChunkIndex + 1;
    while (
      nextIdx < this.activeChunks.length &&
      this.cache.canPreloadNext(currentChunkIndex)
    ) {
      if (!this.cache.has(nextIdx)) {
        const chunk = this.activeChunks[nextIdx];
        if (!chunk || chunk.status !== 'READY') break;
        const stored = await getAudioBlob(chunk.audioKey);
        if (!stored) break;
        this.cache.putChunk(nextIdx, stored.bytes, stored.durationMs, currentChunkIndex);
      }
      nextIdx++;
    }

    this.emit({
      ...this.state,
      windowTelemetry: this.cache.getTelemetry(currentChunkIndex),
    });
  }

  private async handleChunkEnded(): Promise<void> {
    const nextIdx = this.state.currentChunkIndex + 1;
    // Liberación inmediata del fragmento recién terminado
    this.cache.evictOutsideWindow(nextIdx);

    if (nextIdx < this.activeChunks.length) {
      await this.playChunkAt(nextIdx, 0, true);
      return;
    }

    // Fin del capítulo actual -> avance secuencial automático al siguiente capítulo bajo demanda
    if (
      this.activeBook &&
      this.state.chapterIndex + 1 < this.activeChapters.length
    ) {
      await this.goToChapterOffset(1);
    } else {
      this.emit({
        ...this.state,
        isPlaying: false,
        positionMs: this.state.durationMs,
        windowTelemetry: this.cache.getTelemetry(this.state.currentChunkIndex),
      });
      await this.persistCurrentProgress(true);
    }
  }

  private handleTimeUpdate(): void {
    if (!this.audio) return;
    const chunkStart = this.getChunkStartOffsetMs(this.state.currentChunkIndex);
    const currentPosMs = chunkStart + Math.round(this.audio.currentTime * 1000);
    this.emit({
      ...this.state,
      positionMs: Math.min(currentPosMs, Math.max(currentPosMs, this.state.durationMs)),
    });
    void this.persistCurrentProgress(false);
  }

  private updatePlayState(playing: boolean): void {
    this.emit({ ...this.state, isPlaying: playing });
    if (typeof navigator !== 'undefined' && 'mediaSession' in navigator) {
      navigator.mediaSession.playbackState = playing ? 'playing' : 'paused';
    }
  }

  private async persistCurrentProgress(force: boolean): Promise<void> {
    if (!this.activeBook) return;
    const now = Date.now();
    if (!force && now - this.lastSavedTimestamp < 4000) return;
    this.lastSavedTimestamp = now;
    await updateBookProgress(
      this.activeBook.id,
      this.state.chapterIndex,
      this.state.positionMs,
      this.state.playbackSpeed,
    );
  }

  private computeChapterDurationMs(): number {
    if (this.activeChunks.length === 0) return 0;
    return this.activeChunks.reduce((acc, ch) => {
      if (ch.durationMs > 0) return acc + ch.durationMs;
      // Estimación mientras el resto de fragmentos se sintetizan
      return acc + Math.round((ch.charCount / 14.5) * 1000);
    }, 0);
  }

  private getChunkStartOffsetMs(chunkIndex: number): number {
    let offset = 0;
    for (let i = 0; i < chunkIndex && i < this.activeChunks.length; i++) {
      const ch = this.activeChunks[i];
      offset += ch.durationMs > 0 ? ch.durationMs : Math.round((ch.charCount / 14.5) * 1000);
    }
    return offset;
  }

  private locateChunkForPosition(targetMs: number): { chunkIndex: number; offsetMs: number } {
    let acc = 0;
    for (let i = 0; i < this.activeChunks.length; i++) {
      const ch = this.activeChunks[i];
      const dur = ch.durationMs > 0 ? ch.durationMs : Math.round((ch.charCount / 14.5) * 1000);
      if (targetMs < acc + dur || i === this.activeChunks.length - 1) {
        return { chunkIndex: i, offsetMs: Math.max(0, targetMs - acc) };
      }
      acc += dur;
    }
    return { chunkIndex: 0, offsetMs: 0 };
  }

  private updateMediaSessionMetadata(): void {
    if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return;
    const ms = navigator.mediaSession;
    try {
      const artwork: MediaImage[] = this.coverObjectUrl
        ? [{ src: this.coverObjectUrl, sizes: '512x512', type: 'image/jpeg' }]
        : [{ src: '/assets/icon-512.png', sizes: '512x512', type: 'image/png' }];

      ms.metadata = new MediaMetadata({
        title: this.state.chapterTitle || this.state.bookTitle,
        artist: this.state.bookAuthor || 'AudioLibreaMe',
        album: this.state.bookTitle,
        artwork,
      });

      ms.setActionHandler('play', () => void this.togglePlayPause());
      ms.setActionHandler('pause', () => this.pause());
      ms.setActionHandler('seekbackward', () => void this.skipByMs(-15000));
      ms.setActionHandler('seekforward', () => void this.skipByMs(30000));
      ms.setActionHandler('previoustrack', () => void this.goToChapterOffset(-1));
      ms.setActionHandler('nexttrack', () => void this.goToChapterOffset(1));
    } catch {
      // Algunos navegadores restringen handlers específicos
    }
  }
}

export const playbackController = new AudiobookPlaybackController();
