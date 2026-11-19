import React from 'react';
import {
  ArrowLeft,
  Cpu,
  Loader2,
  Pause,
  Play,
  RotateCcw,
  RotateCw,
  SkipBack,
  SkipForward,
} from 'lucide-react';
import { BookCover } from '../components/BookCover';
import {
  formatDurationMs,
  NARRATOR_VOICES,
  PLAYBACK_SPEEDS,
  type Book,
  type PlaybackUiState,
} from '../domain/models';

interface AudiobookPlayerScreenProps {
  book: Book | null;
  playback: PlaybackUiState;
  onBack: () => void;
  onTogglePlay: () => void;
  onSeekToMs: (ms: number) => void;
  onSkipByMs: (deltaMs: number) => void;
  onChangeSpeed: (speed: number) => void;
  onPrevChapter: () => void;
  onNextChapter: () => void;
}

export const AudiobookPlayerScreen: React.FC<AudiobookPlayerScreenProps> = ({
  book,
  playback,
  onBack,
  onTogglePlay,
  onSeekToMs,
  onSkipByMs,
  onChangeSpeed,
  onPrevChapter,
  onNextChapter,
}) => {
  const voice = NARRATOR_VOICES[playback.voiceId];
  const duration = Math.max(1, playback.durationMs);
  const clampedPos = Math.min(playback.positionMs, duration);

  return (
    <div className="screen-container player-screen">
      <header className="top-bar">
        <button type="button" className="icon-btn ghost" onClick={onBack} aria-label="Volver">
          <ArrowLeft size={21} />
        </button>
        <div className="player-header-meta">
          <span className="eyebrow">
            Capítulo {playback.chapterIndex + 1} de {Math.max(1, playback.totalChapters)}
          </span>
        </div>
        <div style={{ width: 40 }} />
      </header>

      <div className="player-main">
        {book && (
          <BookCover
            bookId={book.id}
            title={book.title}
            author={book.author}
            hasCover={book.hasCover}
            className="player-cover"
          />
        )}

        <div className="player-titles">
          <h1 className="player-chapter-title">{playback.chapterTitle || 'Sin capítulo'}</h1>
          <p className="player-book-subtitle">
            {playback.bookTitle} · {playback.bookAuthor}
          </p>
          <span className="meta-chip">{voice.displayName}</span>
        </div>

        <div className="player-scrubber">
          <input
            type="range"
            min={0}
            max={duration}
            value={clampedPos}
            onChange={(e) => onSeekToMs(Number(e.target.value))}
            aria-label="Posición de reproducción"
            className="scrubber-range"
          />
          <div className="scrubber-times">
            <span>{formatDurationMs(clampedPos)}</span>
            <span>{formatDurationMs(playback.durationMs)}</span>
          </div>
        </div>

        <div className="player-controls">
          <button
            type="button"
            className="icon-btn ghost"
            disabled={playback.chapterIndex === 0}
            onClick={onPrevChapter}
            aria-label="Capítulo anterior"
          >
            <SkipBack size={22} />
          </button>

          <button
            type="button"
            className="icon-btn tonal"
            onClick={() => onSkipByMs(-15000)}
            aria-label="Retroceder 15 segundos"
          >
            <RotateCcw size={21} />
          </button>

          <button
            type="button"
            className="play-primary-btn"
            onClick={onTogglePlay}
            aria-label={playback.isPlaying ? 'Pausar' : 'Reproducir'}
          >
            {playback.isBuffering ? (
              <Loader2 size={28} className="spin" />
            ) : playback.isPlaying ? (
              <Pause size={28} />
            ) : (
              <Play size={28} />
            )}
          </button>

          <button
            type="button"
            className="icon-btn tonal"
            onClick={() => onSkipByMs(30000)}
            aria-label="Avanzar 30 segundos"
          >
            <RotateCw size={21} />
          </button>

          <button
            type="button"
            className="icon-btn ghost"
            disabled={playback.chapterIndex >= playback.totalChapters - 1}
            onClick={onNextChapter}
            aria-label="Capítulo siguiente"
          >
            <SkipForward size={22} />
          </button>
        </div>

        <div className="speed-row" role="group" aria-label="Velocidad de reproducción">
          {PLAYBACK_SPEEDS.map((speed) => (
            <button
              key={speed}
              type="button"
              className={`chip-btn ${Math.abs(playback.playbackSpeed - speed) < 0.01 ? 'active' : ''}`}
              onClick={() => onChangeSpeed(speed)}
            >
              {speed}x
            </button>
          ))}
        </div>

        {playback.errorMessage && (
          <div className="alert-banner error" role="alert">
            {playback.errorMessage}
          </div>
        )}

        <div className="telemetry-strip" title="Gestión inteligente de ventana deslizante en RAM">
          <Cpu size={14} />
          <span>
            Ventana RAM: {playback.windowTelemetry.activeObjectUrlCount} frag. activos (
            {playback.windowTelemetry.targetWindowSeconds}s ·{' '}
            {playback.windowTelemetry.releasedChunksCount} liberados)
          </span>
        </div>
      </div>
    </div>
  );
};
