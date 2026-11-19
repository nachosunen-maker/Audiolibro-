import React, { useCallback, useEffect, useState } from 'react';
import { ArchitectureSheet } from './components/ArchitectureSheet';
import { MiniPlayerBar } from './components/MiniPlayerBar';
import { PwaInstallBanner } from './components/PwaInstallBanner';
import {
  DEFAULT_USER_PREFERENCES,
  type Book,
  type Chapter,
  type NarratorVoiceId,
  type PlaybackUiState,
  type ProgressiveChapterState,
  type UserPreferences,
} from './domain/models';
import { playbackController } from './player/playbackController';
import { AudiobookPlayerScreen } from './screens/AudiobookPlayerScreen';
import { BookDetailScreen } from './screens/BookDetailScreen';
import { ChapterReaderScreen } from './screens/ChapterReaderScreen';
import { LibraryScreen } from './screens/LibraryScreen';
import { ProgressiveChapterScreen } from './screens/ProgressiveChapterScreen';
import {
  clearBookAudioCache,
  deleteBookCompletely,
  getAllBooks,
  getBookChapters,
  getUserPreferences,
  importEpubToLibrary,
  requestPersistentStorage,
  saveUserPreferences,
  updateBookPreferredVoice,
} from './storage/db';
import {
  chapterOrchestrator,
  INITIAL_PROGRESSIVE_STATE,
} from './tts/chapterOrchestrator';

type Route =
  | { name: 'library' }
  | { name: 'bookDetail'; bookId: string }
  | { name: 'reader'; bookId: string; chapterIndex: number }
  | { name: 'progressive'; bookId: string; chapterId: string }
  | { name: 'player'; bookId: string };

interface AppProps {
  updateAvailable: boolean;
  onApplyUpdate: () => void;
}

export const App: React.FC<AppProps> = ({ updateAvailable, onApplyUpdate }) => {
  const [route, setRoute] = useState<Route>({ name: 'library' });
  const [books, setBooks] = useState<Book[]>([]);
  const [chaptersByBook, setChaptersByBook] = useState<Record<string, Chapter[]>>({});
  const [preferences, setPreferences] = useState<UserPreferences>(DEFAULT_USER_PREFERENCES);
  const [isImporting, setIsImporting] = useState(false);
  const [importError, setImportError] = useState<string | null>(null);
  const [infoOpen, setInfoOpen] = useState(false);
  const [progressiveState, setProgressiveState] = useState<ProgressiveChapterState>(
    INITIAL_PROGRESSIVE_STATE,
  );
  const [playbackState, setPlaybackState] = useState<PlaybackUiState>(
    playbackController.getState(),
  );

  const refreshBooks = useCallback(async () => {
    const loaded = await getAllBooks();
    setBooks(loaded);
  }, []);

  const loadChapters = useCallback(async (bookId: string) => {
    const chapters = await getBookChapters(bookId);
    setChaptersByBook((prev) => ({ ...prev, [bookId]: chapters }));
    return chapters;
  }, []);

  useEffect(() => {
    void refreshBooks();
    void getUserPreferences().then(setPreferences);
    void requestPersistentStorage();

    const unsubProg = chapterOrchestrator.subscribe((st) => {
      setProgressiveState(st);
      if (st.phase === 'COMPLETED' && st.bookId) {
        void loadChapters(st.bookId);
      }
    });
    const unsubPlay = playbackController.subscribe(setPlaybackState);
    return () => {
      unsubProg();
      unsubPlay();
    };
  }, [refreshBooks, loadChapters]);

  // Aplicar tema claro / oscuro / sistema
  useEffect(() => {
    const root = document.documentElement;
    if (preferences.themeMode === 'system') {
      root.removeAttribute('data-theme');
    } else {
      root.setAttribute('data-theme', preferences.themeMode);
    }
  }, [preferences.themeMode]);

  const handleUpdatePreferences = useCallback((next: UserPreferences) => {
    setPreferences(next);
    void saveUserPreferences(next);
  }, []);

  const handleImportFiles = useCallback(
    async (files: FileList | File[]) => {
      setIsImporting(true);
      setImportError(null);
      try {
        const list = Array.from(files);
        let duplicateCount = 0;
        for (const file of list) {
          const buf = await file.arrayBuffer();
          const res = await importEpubToLibrary(buf, file.name, preferences.defaultVoice);
          if (res.isDuplicate) duplicateCount++;
        }
        await refreshBooks();
        if (duplicateCount > 0 && list.length === duplicateCount) {
          setImportError('Este EPUB ya estaba en tu biblioteca local.');
        }
      } catch (err) {
        setImportError(
          err instanceof Error ? err.message : 'No se pudo importar el archivo EPUB.',
        );
      } finally {
        setIsImporting(false);
      }
    },
    [preferences.defaultVoice, refreshBooks],
  );

  const handleOpenBook = useCallback(
    async (bookId: string) => {
      await loadChapters(bookId);
      setRoute({ name: 'bookDetail', bookId });
    },
    [loadChapters],
  );

  const handleDeleteBook = useCallback(
    async (bookId: string) => {
      if (playbackState.bookId === bookId) {
        playbackController.stopAndRelease();
      }
      if (progressiveState.bookId === bookId) {
        chapterOrchestrator.cancel();
      }
      await deleteBookCompletely(bookId);
      await refreshBooks();
      if (route.name !== 'library') {
        setRoute({ name: 'library' });
      }
    },
    [playbackState.bookId, progressiveState.bookId, refreshBooks, route.name],
  );

  const handleSelectVoice = useCallback(
    async (bookId: string, voiceId: NarratorVoiceId) => {
      await updateBookPreferredVoice(bookId, voiceId);
      await refreshBooks();
    },
    [refreshBooks],
  );

  const handleListenChapter = useCallback(
    async (book: Book, chapter: Chapter) => {
      const voiceId = book.preferredVoice;
      const isSameAsResume =
        book.lastChapterIndex === chapter.chapterIndex && book.lastPositionMs > 0;

      if (chapter.isSynthesized && chapter.synthesizedVoice === voiceId) {
        await playbackController.openChapterSession(book, chapter, voiceId, {
          startPositionMs: isSameAsResume ? book.lastPositionMs : 0,
          autoPlay: true,
        });
        setRoute({ name: 'player', bookId: book.id });
        return;
      }

      // Síntesis progresiva bajo demanda (exclusivamente este capítulo)
      setRoute({ name: 'progressive', bookId: book.id, chapterId: chapter.id });
      void chapterOrchestrator.startChapterSynthesis(book, chapter, voiceId);
    },
    [],
  );

  const handleOpenPlayerFromProgressive = useCallback(async () => {
    if (!progressiveState.bookId || !progressiveState.chapterId) return;
    const book = books.find((b) => b.id === progressiveState.bookId);
    if (!book) return;
    const chapters =
      chaptersByBook[book.id] || (await loadChapters(book.id));
    const chapter = chapters.find((c) => c.id === progressiveState.chapterId);
    if (!chapter) return;

    await playbackController.openChapterSession(book, chapter, progressiveState.voiceId, {
      startPositionMs: 0,
      autoPlay: true,
    });
    setRoute({ name: 'player', bookId: book.id });
  }, [books, chaptersByBook, loadChapters, progressiveState]);

  const activeBook =
    route.name !== 'library' ? books.find((b) => b.id === route.bookId) || null : null;
  const activeChapters = activeBook ? chaptersByBook[activeBook.id] || [] : [];
  const playerBook =
    playbackState.bookId ? books.find((b) => b.id === playbackState.bookId) || null : null;

  return (
    <div className="app-shell">
      <PwaInstallBanner
        updateAvailable={updateAvailable}
        onApplyUpdate={onApplyUpdate}
      />

      <main className="app-main">
        {route.name === 'library' && (
          <LibraryScreen
            books={books}
            isImporting={isImporting}
            importError={importError}
            onImportFiles={handleImportFiles}
            onOpenBook={(id) => void handleOpenBook(id)}
            onDeleteBook={(id) => void handleDeleteBook(id)}
            onOpenInfo={() => setInfoOpen(true)}
          />
        )}

        {route.name === 'bookDetail' && activeBook && (
          <BookDetailScreen
            book={activeBook}
            chapters={activeChapters}
            onBack={() => setRoute({ name: 'library' })}
            onSelectVoice={(voiceId) => void handleSelectVoice(activeBook.id, voiceId)}
            onListenChapter={(ch) => void handleListenChapter(activeBook, ch)}
            onReadChapter={(ch) =>
              setRoute({
                name: 'reader',
                bookId: activeBook.id,
                chapterIndex: ch.chapterIndex,
              })
            }
            onClearAudioCache={async () => {
              await clearBookAudioCache(activeBook.id);
              await loadChapters(activeBook.id);
            }}
            onDeleteBook={() => void handleDeleteBook(activeBook.id)}
          />
        )}

        {route.name === 'reader' &&
          activeBook &&
          activeChapters[route.chapterIndex] && (
            <ChapterReaderScreen
              book={activeBook}
              chapter={activeChapters[route.chapterIndex]}
              totalChapters={activeChapters.length}
              fontSizeSp={preferences.readerFontSizeSp}
              onChangeFontSize={(size) =>
                handleUpdatePreferences({ ...preferences, readerFontSizeSp: size })
              }
              onBack={() => setRoute({ name: 'bookDetail', bookId: activeBook.id })}
              onPrevChapter={() =>
                setRoute({
                  name: 'reader',
                  bookId: activeBook.id,
                  chapterIndex: Math.max(0, route.chapterIndex - 1),
                })
              }
              onNextChapter={() =>
                setRoute({
                  name: 'reader',
                  bookId: activeBook.id,
                  chapterIndex: Math.min(activeChapters.length - 1, route.chapterIndex + 1),
                })
              }
              onListenThisChapter={() =>
                void handleListenChapter(activeBook, activeChapters[route.chapterIndex])
              }
            />
          )}

        {route.name === 'progressive' && (
          <ProgressiveChapterScreen
            state={progressiveState}
            onBack={() =>
              setRoute(
                progressiveState.bookId
                  ? { name: 'bookDetail', bookId: progressiveState.bookId }
                  : { name: 'library' },
              )
            }
            onOpenPlayer={() => void handleOpenPlayerFromProgressive()}
            onCancel={() => chapterOrchestrator.cancel()}
            onRetry={() => {
              if (!activeBook) return;
              const ch = activeChapters.find((c) => c.id === route.chapterId);
              if (ch) {
                void chapterOrchestrator.startChapterSynthesis(
                  activeBook,
                  ch,
                  activeBook.preferredVoice,
                );
              }
            }}
          />
        )}

        {route.name === 'player' && (
          <AudiobookPlayerScreen
            book={playerBook || activeBook}
            playback={playbackState}
            onBack={() =>
              setRoute(
                playbackState.bookId
                  ? { name: 'bookDetail', bookId: playbackState.bookId }
                  : { name: 'library' },
              )
            }
            onTogglePlay={() => void playbackController.togglePlayPause()}
            onSeekToMs={(ms) => void playbackController.seekToMs(ms)}
            onSkipByMs={(delta) => void playbackController.skipByMs(delta)}
            onChangeSpeed={(speed) => void playbackController.setPlaybackSpeed(speed)}
            onPrevChapter={() => void playbackController.goToChapterOffset(-1)}
            onNextChapter={() => void playbackController.goToChapterOffset(1)}
          />
        )}
      </main>

      {route.name !== 'player' && (
        <MiniPlayerBar
          playback={playbackState}
          onOpenPlayer={() => {
            if (playbackState.bookId) {
              setRoute({ name: 'player', bookId: playbackState.bookId });
            }
          }}
          onTogglePlay={() => void playbackController.togglePlayPause()}
          onClose={() => playbackController.stopAndRelease()}
        />
      )}

      <ArchitectureSheet
        open={infoOpen}
        onClose={() => setInfoOpen(false)}
        preferences={preferences}
        onUpdatePreferences={handleUpdatePreferences}
        onLibraryChanged={() => void refreshBooks()}
      />
    </div>
  );
};
