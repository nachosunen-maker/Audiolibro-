import React from 'react';
import {
  ArrowLeft,
  CheckCircle2,
  Headphones,
  Loader2,
  PauseCircle,
  RefreshCw,
} from 'lucide-react';
import { computeChapterProgress } from '../domain/progressiveProgress';
import {
  formatIntegerEs,
  NARRATOR_VOICES,
  type ProgressiveChapterState,
} from '../domain/models';

interface ProgressiveChapterScreenProps {
  state: ProgressiveChapterState;
  onBack: () => void;
  onOpenPlayer: () => void;
  onCancel: () => void;
  onRetry: () => void;
}

export const ProgressiveChapterScreen: React.FC<ProgressiveChapterScreenProps> = ({
  state,
  onBack,
  onOpenPlayer,
  onCancel,
  onRetry,
}) => {
  const progress = computeChapterProgress(
    state.completedChunks,
    state.totalChunks,
    state.synthesizedChapterChars,
    state.totalChapterChars,
  );
  const voice = NARRATOR_VOICES[state.voiceId];
  const isBusy =
    state.phase === 'DOWNLOADING_MODEL' ||
    state.phase === 'PREPARING' ||
    state.phase === 'SYNTHESIZING' ||
    state.phase === 'READY_TO_PLAY';

  return (
    <div className="screen-container">
      <header className="top-bar">
        <button type="button" className="icon-btn ghost" onClick={onBack} aria-label="Volver">
          <ArrowLeft size={21} />
        </button>
        <h2 className="top-bar-title">Síntesis progresiva</h2>
        <div style={{ width: 40 }} />
      </header>

      <section className="surface-card progressive-card">
        <span className="eyebrow">
          Capítulo {state.chapterIndex + 1} · {voice.displayName}
        </span>
        <h1 className="progressive-title">{state.chapterTitle || state.bookTitle}</h1>
        <p className="helper-note">{state.bookTitle}</p>

        {state.phase === 'DOWNLOADING_MODEL' && (
          <div className="progress-block">
            <div className="progress-header">
              <span>Descargando modelo neural Piper (1ª vez)</span>
              <strong>{Math.round(state.modelDownloadProgress * 100)}%</strong>
            </div>
            <div className="progress-track">
              <div
                className="progress-fill secondary"
                style={{ width: `${Math.round(state.modelDownloadProgress * 100)}%` }}
              />
            </div>
            <p className="helper-note">{state.modelDownloadMessage}</p>
          </div>
        )}

        <div className="progress-block">
          <div className="progress-header">
            <span>Progreso del capítulo</span>
            <strong>{progress.percent}%</strong>
          </div>
          <div className="progress-track">
            <div
              className="progress-fill"
              style={{ width: `${progress.percent}%` }}
            />
          </div>
          <div className="progress-submetrics">
            <span>
              Fragmentos: {progress.completedChunks}/{progress.totalChunks}
            </span>
            <span>
              {formatIntegerEs(progress.synthesizedChars)} /{' '}
              {formatIntegerEs(progress.totalChars)} car.
            </span>
          </div>
        </div>

        <div className="status-pill-row">
          {state.phase === 'COMPLETED' ? (
            <span className="status-chip success">
              <CheckCircle2 size={15} /> Capítulo completo offline
            </span>
          ) : isBusy ? (
            <span className="status-chip working">
              <Loader2 size={15} className="spin" /> {state.statusMessage}
            </span>
          ) : (
            <span className="status-chip">{state.statusMessage}</span>
          )}
          {state.realtimeFactor !== null && (
            <span className="meta-chip">
              RTF {state.realtimeFactor.toFixed(2)}x
            </span>
          )}
        </div>

        {state.errorMessage && (
          <div className="alert-banner error" role="alert">
            {state.errorMessage}
          </div>
        )}

        <div className="button-stack">
          {state.canStartPlayback && (
            <button type="button" className="btn-primary large" onClick={onOpenPlayer}>
              <Headphones size={18} />
              <span>Escuchar capítulo ahora</span>
            </button>
          )}

          {isBusy && (
            <button type="button" className="btn-secondary" onClick={onCancel}>
              <PauseCircle size={17} />
              <span>Pausar generación</span>
            </button>
          )}

          {(state.phase === 'ERROR' || state.phase === 'CANCELLED') && (
            <button type="button" className="btn-primary" onClick={onRetry}>
              <RefreshCw size={17} />
              <span>Reanudar síntesis</span>
            </button>
          )}
        </div>
      </section>
    </div>
  );
};
