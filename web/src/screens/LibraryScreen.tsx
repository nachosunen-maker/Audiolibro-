import React, { useMemo, useRef, useState } from 'react';
import {
  BookOpen,
  Headphones,
  Info,
  Plus,
  Search,
  Sparkles,
  Trash2,
  Upload,
} from 'lucide-react';
import { BookCover } from '../components/BookCover';
import {
  formatDurationMs,
  formatHumanDurationMs,
  type Book,
} from '../domain/models';

interface LibraryScreenProps {
  books: Book[];
  isImporting: boolean;
  importError: string | null;
  onImportFiles: (files: FileList | File[]) => void;
  onOpenBook: (bookId: string) => void;
  onDeleteBook: (bookId: string) => void;
  onOpenInfo: () => void;
}

export const LibraryScreen: React.FC<LibraryScreenProps> = ({
  books,
  isImporting,
  importError,
  onImportFiles,
  onOpenBook,
  onDeleteBook,
  onOpenInfo,
}) => {
  const [query, setQuery] = useState('');
  const [isDragging, setIsDragging] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const filteredBooks = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return books;
    return books.filter(
      (b) =>
        b.title.toLowerCase().includes(q) ||
        b.author.toLowerCase().includes(q),
    );
  }, [books, query]);

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      onImportFiles(e.dataTransfer.files);
    }
  };

  return (
    <div
      className={`screen-container ${isDragging ? 'drag-over' : ''}`}
      onDragOver={(e) => {
        e.preventDefault();
        setIsDragging(true);
      }}
      onDragLeave={() => setIsDragging(false)}
      onDrop={handleDrop}
    >
      <header className="top-bar">
        <div className="brand-lockup">
          <h1 className="app-wordmark">AudioLibreaMe</h1>
          <span className="brand-tagline">Biblioteca local · Voz Piper es-ES</span>
        </div>
        <button
          type="button"
          className="icon-btn ghost"
          onClick={onOpenInfo}
          aria-label="Ajustes, privacidad y copia de seguridad"
        >
          <Info size={21} />
        </button>
      </header>

      {importError && (
        <div className="alert-banner error" role="alert">
          {importError}
        </div>
      )}

      {isImporting && (
        <div className="alert-banner info" role="status">
          Analizando EPUB localmente y extrayendo capítulos…
        </div>
      )}

      {books.length === 0 ? (
        <section className="empty-library-card">
          <img
            src="/assets/img_empty_library.jpg"
            alt="Ilustración editorial de biblioteca de AudioLibreaMe"
            className="empty-library-art"
          />
          <h2 className="empty-title">Tu biblioteca local te espera</h2>
          <p className="empty-description">
            Importa tus libros en formato EPUB sin DRM. Se leen y narran con voz neural en español
            directamente en tu dispositivo, sin subir archivos a la nube.
          </p>
          <button
            type="button"
            className="btn-primary large"
            onClick={() => fileInputRef.current?.click()}
            disabled={isImporting}
          >
            <Upload size={18} />
            <span>Importar primer EPUB</span>
          </button>
          <div className="feature-pills">
            <span className="pill">
              <Sparkles size={13} /> Piper VITS es-ES
            </span>
            <span className="pill">
              <Headphones size={13} /> Sin conexión
            </span>
            <span className="pill">
              <BookOpen size={13} /> Bajo demanda
            </span>
          </div>
        </section>
      ) : (
        <>
          <div className="search-bar">
            <Search size={17} className="search-icon" />
            <input
              type="search"
              placeholder="Buscar por título o autor…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              aria-label="Buscar en la biblioteca"
            />
          </div>

          <div className="library-grid">
            {filteredBooks.map((book) => (
              <article key={book.id} className="book-card">
                <button
                  type="button"
                  className="book-card-main"
                  onClick={() => onOpenBook(book.id)}
                >
                  <BookCover
                    bookId={book.id}
                    title={book.title}
                    author={book.author}
                    hasCover={book.hasCover}
                    className="book-card-cover"
                  />
                  <div className="book-card-meta">
                    <h3 className="book-card-title">{book.title}</h3>
                    <p className="book-card-author">{book.author}</p>
                    <div className="book-card-chips">
                      <span className="meta-chip">{book.chapterCount} cap.</span>
                      <span className="meta-chip">
                        {formatHumanDurationMs(book.estimatedDurationMs)}
                      </span>
                    </div>
                    {book.lastPositionMs > 0 && (
                      <p className="book-resume-hint">
                        Cap. {book.lastChapterIndex + 1} · {formatDurationMs(book.lastPositionMs)}
                      </p>
                    )}
                  </div>
                </button>
                <button
                  type="button"
                  className="icon-btn ghost card-delete-btn"
                  onClick={() => {
                    if (
                      window.confirm(
                        `¿Eliminar «${book.title}» y todo su audio generado de este dispositivo?`,
                      )
                    ) {
                      onDeleteBook(book.id);
                    }
                  }}
                  aria-label={`Eliminar ${book.title}`}
                >
                  <Trash2 size={16} />
                </button>
              </article>
            ))}
          </div>
        </>
      )}

      <button
        type="button"
        className="fab-primary"
        onClick={() => fileInputRef.current?.click()}
        disabled={isImporting}
        aria-label="Importar archivo EPUB"
      >
        <Plus size={22} />
        <span>Importar EPUB</span>
      </button>

      <input
        ref={fileInputRef}
        type="file"
        accept=".epub,application/epub+zip"
        multiple
        hidden
        onChange={(e) => {
          if (e.target.files && e.target.files.length > 0) {
            onImportFiles(e.target.files);
          }
          e.target.value = '';
        }}
      />
    </div>
  );
};
