import React from 'react';
import {
  ArrowLeft,
  ChevronLeft,
  ChevronRight,
  Headphones,
  Minus,
  Plus,
} from 'lucide-react';
import type { Book, Chapter } from '../domain/models';

interface ChapterReaderScreenProps {
  book: Book;
  chapter: Chapter;
  totalChapters: number;
  fontSizeSp: number;
  onChangeFontSize: (next: number) => void;
  onBack: () => void;
  onPrevChapter: () => void;
  onNextChapter: () => void;
  onListenThisChapter: () => void;
}

export const ChapterReaderScreen: React.FC<ChapterReaderScreenProps> = ({
  book,
  chapter,
  totalChapters,
  fontSizeSp,
  onChangeFontSize,
  onBack,
  onPrevChapter,
  onNextChapter,
  onListenThisChapter,
}) => {
  const paragraphs = chapter.textContent
    .split(/\n{2,}/)
    .map((p) => p.trim())
    .filter(Boolean);

  return (
    <div className="screen-container reader-screen">
      <header className="top-bar">
        <button type="button" className="icon-btn ghost" onClick={onBack} aria-label="Volver al libro">
          <ArrowLeft size={21} />
        </button>
        <div className="reader-header-titles">
          <span className="reader-book-name">{book.title}</span>
          <span className="reader-chapter-indicator">
            Capítulo {chapter.chapterIndex + 1} de {totalChapters}
          </span>
        </div>
        <div className="reader-font-controls">
          <button
            type="button"
            className="icon-btn ghost"
            onClick={() => onChangeFontSize(Math.max(14, fontSizeSp - 2))}
            aria-label="Reducir tamaño de texto"
          >
            <Minus size={16} />
          </button>
          <button
            type="button"
            className="icon-btn ghost"
            onClick={() => onChangeFontSize(Math.min(28, fontSizeSp + 2))}
            aria-label="Aumentar tamaño de texto"
          >
            <Plus size={16} />
          </button>
        </div>
      </header>

      <article className="reader-article" style={{ fontSize: `${fontSizeSp}px` }}>
        <h1 className="reader-chapter-title">{chapter.title}</h1>
        {paragraphs.map((p, idx) => (
          <p key={idx} className="reader-paragraph">
            {p}
          </p>
        ))}
      </article>

      <footer className="reader-footer">
        <button
          type="button"
          className="btn-secondary"
          disabled={chapter.chapterIndex === 0}
          onClick={onPrevChapter}
        >
          <ChevronLeft size={17} /> Anterior
        </button>

        <button type="button" className="btn-primary" onClick={onListenThisChapter}>
          <Headphones size={17} /> Escuchar
        </button>

        <button
          type="button"
          className="btn-secondary"
          disabled={chapter.chapterIndex >= totalChapters - 1}
          onClick={onNextChapter}
        >
          Siguiente <ChevronRight size={17} />
        </button>
      </footer>
    </div>
  );
};
