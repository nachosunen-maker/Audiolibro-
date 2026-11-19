import React, { useEffect, useRef, useState } from 'react';
import {
  Download,
  HardDrive,
  Moon,
  Scale,
  ShieldCheck,
  Sun,
  Monitor,
  Upload,
  X,
} from 'lucide-react';
import { formatBytes, type UserPreferences } from '../domain/models';
import {
  exportLibraryBackup,
  getStorageUsageSummary,
  requestPersistentStorage,
  restoreLibraryBackup,
  type StorageUsageSummary,
} from '../storage/db';

interface ArchitectureSheetProps {
  open: boolean;
  onClose: () => void;
  preferences: UserPreferences;
  onUpdatePreferences: (prefs: UserPreferences) => void;
  onLibraryChanged: () => void;
}

export const ArchitectureSheet: React.FC<ArchitectureSheetProps> = ({
  open,
  onClose,
  preferences,
  onUpdatePreferences,
  onLibraryChanged,
}) => {
  const [usage, setUsage] = useState<StorageUsageSummary | null>(null);
  const [persisted, setPersisted] = useState<boolean | null>(null);
  const [showLegal, setShowLegal] = useState(false);
  const [backupMessage, setBackupMessage] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!open) return;
    setBackupMessage(null);
    void getStorageUsageSummary().then(setUsage);
    void requestPersistentStorage().then((res) => setPersisted(res.persisted));
  }, [open]);

  if (!open) return null;

  const handleExportBackup = async () => {
    try {
      const blob = await exportLibraryBackup();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `AudioLibreaMe-copia-${new Date().toISOString().slice(0, 10)}.json`;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 4000);
      setBackupMessage('Copia de seguridad descargada correctamente.');
    } catch {
      setBackupMessage('No se pudo generar la copia de seguridad.');
    }
  };

  const handleRestoreFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      const text = await file.text();
      const result = await restoreLibraryBackup(text);
      setBackupMessage(
        `Restauración completada: ${result.imported} libro(s) importado(s), ${result.skippedDuplicates} duplicado(s) omitido(s).`,
      );
      onLibraryChanged();
      void getStorageUsageSummary().then(setUsage);
    } catch (err) {
      setBackupMessage(
        err instanceof Error ? err.message : 'Error al restaurar la copia de seguridad.',
      );
    } finally {
      e.target.value = '';
    }
  };

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label="Acerca de AudioLibreaMe">
      <div className="sheet-panel">
        <div className="sheet-header">
          <div>
            <h2 className="sheet-title">AudioLibreaMe Web (PWA)</h2>
            <p className="sheet-subtitle">Arquitectura local, privacidad y copia de seguridad</p>
          </div>
          <button type="button" className="icon-btn ghost" onClick={onClose} aria-label="Cerrar">
            <X size={20} />
          </button>
        </div>

        <div className="sheet-body">
          <section className="info-card">
            <h3>Tema visual</h3>
            <div className="segmented-row">
              {(
                [
                  { id: 'system', label: 'Sistema', icon: Monitor },
                  { id: 'light', label: 'Papel y Tinta', icon: Sun },
                  { id: 'dark', label: 'Tinta Nocturna', icon: Moon },
                ] as const
              ).map(({ id, label, icon: Icon }) => (
                <button
                  key={id}
                  type="button"
                  className={`chip-btn ${preferences.themeMode === id ? 'active' : ''}`}
                  onClick={() => onUpdatePreferences({ ...preferences, themeMode: id })}
                >
                  <Icon size={15} />
                  <span>{label}</span>
                </button>
              ))}
            </div>
          </section>

          <section className="info-card">
            <h3>
              <ShieldCheck size={16} /> Privacidad y motor de voz local
            </h3>
            <ul className="clean-list">
              <li>
                <strong>100% local en tu dispositivo:</strong> Tus EPUB, portadas, progreso y
                audiolibros se guardan únicamente en IndexedDB de este navegador. Nunca se suben a
                ningún servidor externo.
              </li>
              <li>
                <strong>Motor neural Piper VITS (es_ES-sharvard-medium):</strong> Mismo modelo
                neuronal español que la versión Android (voces masculina y femenina a 22.050 Hz),
                ejecutado en WebAssembly + ONNX Runtime Web dentro de un Web Worker.
              </li>
              <li>
                <strong>Síntesis bajo demanda por capítulo:</strong> Sin conversión masiva del libro
                completo y con ventana deslizante en RAM (20–30 s) que libera cada fragmento al
                terminar de reproducirse.
              </li>
            </ul>
          </section>

          <section className="info-card">
            <h3>
              <HardDrive size={16} /> Almacenamiento local y respaldo
            </h3>
            {usage && (
              <div className="stats-grid">
                <div>
                  <span className="stat-label">Libros guardados</span>
                  <strong className="stat-value">{usage.bookCount}</strong>
                </div>
                <div>
                  <span className="stat-label">Audio en caché</span>
                  <strong className="stat-value">{formatBytes(usage.cachedAudioBytes)}</strong>
                </div>
                <div>
                  <span className="stat-label">Persistencia</span>
                  <strong className="stat-value">
                    {persisted === null ? 'Comprobando…' : persisted ? 'Protegida' : 'Estándar'}
                  </strong>
                </div>
              </div>
            )}
            <div className="button-row" style={{ marginTop: 12 }}>
              <button type="button" className="btn-secondary" onClick={handleExportBackup}>
                <Download size={16} /> Exportar copia
              </button>
              <button
                type="button"
                className="btn-secondary"
                onClick={() => fileInputRef.current?.click()}
              >
                <Upload size={16} /> Restaurar copia
              </button>
              <input
                ref={fileInputRef}
                type="file"
                accept="application/json,.json"
                hidden
                onChange={handleRestoreFile}
              />
            </div>
            {backupMessage && <p className="helper-note">{backupMessage}</p>}
          </section>

          <section className="info-card">
            <button
              type="button"
              className="btn-ghost-link"
              onClick={() => setShowLegal((v) => !v)}
            >
              <Scale size={16} />
              <span>{showLegal ? 'Ocultar avisos legales y licencias' : 'Ver avisos legales y licencias'}</span>
            </button>
            {showLegal && (
              <div className="legal-box">
                <p>
                  <strong>AudioLibreaMe</strong> es una herramienta local para lectura y conversión
                  personal de archivos EPUB sin DRM propiedad del usuario.
                </p>
                <p>
                  <strong>Voz Piper VITS (es_ES-sharvard-medium):</strong> Distribuida bajo licencia
                  abierta del proyecto Rhasspy / Piper y corpus Sharvard.
                </p>
                <p>
                  <strong>eSpeak NG (piper-phonemize WASM):</strong> Licencia GPLv3 para fonetización
                  local en el navegador.
                </p>
                <p>
                  <strong>ONNX Runtime Web, JSZip, idb, React, Lucide:</strong> Licencias MIT /
                  Apache 2.0.
                </p>
              </div>
            )}
          </section>
        </div>
      </div>
    </div>
  );
};
