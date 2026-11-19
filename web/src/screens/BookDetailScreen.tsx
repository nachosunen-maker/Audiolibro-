import React from 'react';
import {
  ArrowLeft,
  BookOpen,
  CheckCircle2,
  Download,
  Headphones,
  Play,
  Trash2,
  Volume2,
} from 'lucide-react';
import { BookCover } from '../components/BookCover';
import {
  formatBytes,
  formatDurationMs,
  formatHumanDurationMs,
  NARRATOR_VOICE_LIST,
  type Book,
  type Chapter,
  type NarratorVoiceId,
} from '../domain/models';
import { getAudioBlob, makeChapterAudioKey } from '../storage/db';

interface BookDetailScreenProps {
  book: Book;
  chapters: Chapter[];
  onBack: () => void;
  onSelectVoice: (voiceId: NarratorVoiceId) => void;
  onListenChapter: (chapter: Chapter) => void;
  onReadChapter: (chapter: Chapter) => void;
  onClearAudioCache: () => void;
  onDeleteBook: () => void;
}

export const BookDetailScreen: React.FC<BookDetailScreenProps> = ({
  book,
  chapters,
  onBack,
  onSelectVoice,
  onListenChapter,
  onReadChapter,
  onClearAudioCache,
  onDeleteBook,
}) => {
  const resumeChapter = chapters[book.lastChapterIndex] || chapters[0];
  const synthesizedChapters = chapters.filter((c) => c.isSynthesized);
  const totalAudioBytes = synthesizedChapters.reduce((acc, c) => acc + c.audioSizeBytes, 0);

  const handleExportChapterWav = async (chapter: Chapter) => {
    const voiceId = chapter.synthesizedVoice || book.preferredVoice;
    const blobRecord = await getAudioBlob(makeChapterAudioKey(chapter.id, voiceId));
    if (!blobRecord) return;
    const copy = new Uint8Array(blobRecord.bytes.byteLength);
    copy.set(blobRecord.bytes);
    const wavBlob = new Blob([copy.buffer], { type: 'audio/wav' });
    const url = URL.createObjectURL(wavBlob);
    const a = document.createElement('a');
    const safeTitle = `${book.title}-Cap${chapter.chapterIndex + 1}`.replace(/[^\w\-áéíóúñÁÉÍÓÚÑ ]/g, '');
    a.href = url;
    a.download = `${safeTitle}.wav`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 4000);
  };

  return (
    <div className="screen-container">
      <header className="top-bar">
        <button type="button" className="icon-btn ghost" onClick={onBack} aria-label="Volver a la biblioteca">
          <ArrowLeft size={21} />
        </button>
        <h2 className="top-bar-title">Detalle del libro</h2>
        <div style={{ width: 40 }} />
      </header>

      <section className="book-hero-card">
        <BookCover
          bookId={book.id}
          title={book.title}
          author={book.author}
          hasCover={book.hasCover}
          className="book-hero-cover"
        />
        <div className="book-hero-info">
          <h1 className="book-hero-title">{book.title}</h1>
          <p className="book-hero-author">{book.author}</p>
          <div className="book-card-chips">
            <span className="meta-chip">{book.chapterCount} capítulos</span>
            <span className="meta-chip">{formatHumanDurationMs(book.estimatedDurationMs)}</span>
            <span className="meta-chip">{formatBytes(book.fileSizeBytes)}</span>
          </div>
          {resumeChapter && (
            <button
              type="button"
              className="btn-primary"
              style={{ marginTop: 14 }}
              onClick={() => onListenChapter(resumeChapter)}
            >
              <Play size={17} />
              <span>
                {book.lastPositionMs > 0
                  ? `Continuar · Cap. ${resumeChapter.chapterIndex + 1} (${formatDurationMs(book.lastPositionMs)})`
                  : `Escuchar desde Cap. ${resumeChapter.chapterIndex + 1}`}
              </span>
            </button>
          )}
        </div>
      </section>

      <section className="surface-card">
        <div className="section-heading">
          <Volume2 size={18} />
          <h3>Voz neural local (Piper VITS es-ES)</h3>
        </div>
        <div className="segmented-row">
          {NARRATOR_VOICE_LIST.map((voice) => (
            <button
              key={voice.id}
              type="button"
              className={`chip-btn ${book.preferredVoice === voice.id ? 'active' : ''}`}
              onClick={() => onSelectVoice(voice.id)}
            >
              <span>{voice.displayName}</span>
            </button>
          ))}
        </div>
        <p className="helper-note">
          La narración se sintetiza bajo demanda al abrir cada capítulo, sin ocupar memoria con el
          libro completo.
        </p>
      </section>

      <section className="surface-card">
        <div className="section-heading">
          <Headphones size={18} />
          <h3>Capítulos ({chapters.length})</h3>
        </div>
        <div className="chapter-list">
          {chapters.map((chapter) => (
            <div key={chapter.id} className="chapter-row">
              <div className="chapter-row-info">
                <div className="chapter-row-title-line">
                  <span className="chapter-index">{chapter.chapterIndex + 1}.</span>
                  <span className="chapter-title">{chapter.title}</span>
                  {chapter.isSynthesized && (
                    <span className="cached-badge" title="Capítulo guardado en caché offline">
                      <CheckCircle2 size={13} /> Offline
                    </span>
                  )}
                </div>
                <span className="chapter-meta">
                  {chapter.wordCount} palabras ·{' '}
                  {chapter.isSynthesized
                    ? formatDurationMs(chapter.synthesizedDurationMs)
                    : formatHumanDurationMs(chapter.estimatedDurationMs)}
                </span>
              </div>

              <div className="chapter-row-actions">
                <button
                  type="button"
                  className="icon-btn ghost"
                  onClick={() => onReadChapter(chapter)}
                  aria-label={`Leer ${chapter.title}`}
                  title="Leer texto"
                >
                  <BookOpen size={18} />
                </button>
                {chapter.isSynthesized && (
                  <button
                    type="button"
                    className="icon-btn ghost"
                    onClick={() => void handleExportChapterWav(chapter)}
                    aria-label={`Exportar WAV de ${chapter.title}`}
                    title="Exportar WAV"
                  >
                    <Download size={18} />
                  </button>
                )}
                <button
                  type="button"
                  className="btn-tonal small"
                  onClick={() => onListenChapter(chapter)}
                >
                  <Headphones size={15} />
                  <span>Escuchar</span>
                </button>
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className="surface-card">
        <div className="section-heading">
          <h3>Almacenamiento del libro</h3>
        </div>
        <p className="helper-note">
          {synthesizedChapters.length} de {chapters.length} capítulos sintetizados en caché local (
          {formatBytes(totalAudioBytes)}).
        </p>
        <div className="button-row" style={{ marginTop: 10 }}>
          {synthesizedChapters.length > 0 && (
            <button type="button" className="btn-secondary" onClick={onClearAudioCache}>
              <Trash2 size={15} /> Liberar audio en caché
            </button>
          )}
          <button
            type="button"
            className="btn-danger-outline"
            onClick={() => {
              if (
                window.confirm(
                  `¿Eliminar «${book.title}» completamente de este dispositivo?`,
                )
              ) {
                onDeleteBook();
              }
            }}
          >
            <Trash2 size={15} /> Eliminar libro
          </button>
        </div>
      </section>
    </div>
  );
};
