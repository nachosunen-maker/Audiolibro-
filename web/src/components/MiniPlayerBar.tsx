import React from 'react';
import { Pause, Play, X } from 'lucide-react';
import { formatDurationMs, type PlaybackUiState } from '../domain/models';

interface MiniPlayerBarProps {
  playback: PlaybackUiState;
  onOpenPlayer: () => void;
  onTogglePlay: () => void;
  onClose: () => void;
}

export const MiniPlayerBar: React.FC<MiniPlayerBarProps> = ({
  playback,
  onOpenPlayer,
  onTogglePlay,
  onClose,
}) => {
  if (!playback.bookId || !playback.chapterId) return null;

  const progressFraction =
    playback.durationMs > 0
      ? Math.min(1, Math.max(0, playback.positionMs / playback.durationMs))
      : 0;

  return (
    <div className="mini-player" role="region" aria-label="Reproductor en curso">
      <div className="mini-player-progress-track">
        <div
          className="mini-player-progress-fill"
          style={{ width: `${Math.round(progressFraction * 100)}%` }}
        />
      </div>
      <div className="mini-player-content">
        <button
          type="button"
          className="mini-player-info"
          onClick={onOpenPlayer}
          aria-label="Abrir reproductor completo"
        >
          <span className="mini-player-title">{playback.bookTitle}</span>
          <span className="mini-player-subtitle">
            Cap. {playback.chapterIndex + 1} · {playback.chapterTitle} ·{' '}
            {formatDurationMs(playback.positionMs)}
          </span>
        </button>

        <div className="mini-player-actions">
          <button
            type="button"
            className="icon-btn primary-tonal"
            onClick={onTogglePlay}
            aria-label={playback.isPlaying ? 'Pausar reproducción' : 'Reanudar reproducción'}
          >
            {playback.isPlaying ? <Pause size={20} /> : <Play size={20} />}
          </button>
          <button
            type="button"
            className="icon-btn ghost"
            onClick={onClose}
            aria-label="Cerrar reproductor"
          >
            <X size={18} />
          </button>
        </div>
      </div>
    </div>
  );
};
