import React, { useEffect, useState } from 'react';
import { Download, RefreshCw, Share, X } from 'lucide-react';

interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

interface PwaInstallBannerProps {
  updateAvailable: boolean;
  onApplyUpdate: () => void;
}

export const PwaInstallBanner: React.FC<PwaInstallBannerProps> = ({
  updateAvailable,
  onApplyUpdate,
}) => {
  const [deferredPrompt, setDeferredPrompt] = useState<BeforeInstallPromptEvent | null>(null);
  const [showIosHint, setShowIosHint] = useState(false);
  const [dismissed, setDismissed] = useState(false);

  useEffect(() => {
    const isStandalone =
      window.matchMedia('(display-mode: standalone)').matches ||
      (window.navigator as Navigator & { standalone?: boolean }).standalone === true;

    if (isStandalone) return;

    const isIos = /iPhone|iPad|iPod/i.test(navigator.userAgent);
    const isSafari = /Safari/i.test(navigator.userAgent) && !/CriOS|FxiOS/i.test(navigator.userAgent);
    if (isIos && isSafari) {
      setShowIosHint(true);
    }

    const handler = (e: Event) => {
      e.preventDefault();
      setDeferredPrompt(e as BeforeInstallPromptEvent);
    };
    window.addEventListener('beforeinstallprompt', handler);
    return () => window.removeEventListener('beforeinstallprompt', handler);
  }, []);

  if (updateAvailable) {
    return (
      <div className="pwa-banner update" role="status">
        <div className="pwa-banner-text">
          <strong>Nueva versión disponible</strong>
          <span>Actualiza AudioLibreaMe sin perder tus libros ni tu progreso.</span>
        </div>
        <button type="button" className="btn-primary small" onClick={onApplyUpdate}>
          <RefreshCw size={15} /> Actualizar
        </button>
      </div>
    );
  }

  if (dismissed) return null;

  if (deferredPrompt) {
    return (
      <div className="pwa-banner" role="region" aria-label="Instalar aplicación">
        <div className="pwa-banner-text">
          <strong>Instala AudioLibreaMe</strong>
          <span>Úsala como app independiente y escucha tus capítulos sin conexión.</span>
        </div>
        <div className="pwa-banner-actions">
          <button
            type="button"
            className="btn-primary small"
            onClick={async () => {
              await deferredPrompt.prompt();
              await deferredPrompt.userChoice;
              setDeferredPrompt(null);
            }}
          >
            <Download size={15} /> Instalar
          </button>
          <button
            type="button"
            className="icon-btn ghost"
            onClick={() => setDismissed(true)}
            aria-label="Ocultar aviso de instalación"
          >
            <X size={16} />
          </button>
        </div>
      </div>
    );
  }

  if (showIosHint) {
    return (
      <div className="pwa-banner" role="region" aria-label="Instalar en iPhone o iPad">
        <div className="pwa-banner-text">
          <strong>Instalar en iPhone / iPad</strong>
          <span>
            Pulsa <Share size={13} style={{ verticalAlign: 'middle' }} /> Compartir y elige{' '}
            <em>Añadir a pantalla de inicio</em>.
          </span>
        </div>
        <button
          type="button"
          className="icon-btn ghost"
          onClick={() => setDismissed(true)}
          aria-label="Cerrar sugerencia iOS"
        >
          <X size={16} />
        </button>
      </div>
    );
  }

  return null;
};
