import React, { useEffect, useState } from 'react';
import { getBookCoverBlob } from '../storage/db';

interface BookCoverProps {
  bookId: string;
  title: string;
  author: string;
  hasCover: boolean;
  className?: string;
}

const COVER_PALETTES = [
  ['#9C4221', '#7A3116'],
  ['#3E5641', '#2B3E2E'],
  ['#6B4E3D', '#4A3427'],
  ['#7D5A38', '#593F26'],
  ['#4A5D5E', '#314041'],
];

function pickPalette(seed: string): [string, string] {
  let hash = 0;
  for (let i = 0; i < seed.length; i++) {
    hash = (hash * 31 + seed.charCodeAt(i)) | 0;
  }
  const idx = Math.abs(hash) % COVER_PALETTES.length;
  return COVER_PALETTES[idx] as [string, string];
}

export const BookCover: React.FC<BookCoverProps> = ({
  bookId,
  title,
  author,
  hasCover,
  className = '',
}) => {
  const [coverUrl, setCoverUrl] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    let objectUrl: string | null = null;

    if (!hasCover) {
      setCoverUrl(null);
      return;
    }

    void getBookCoverBlob(bookId).then((blob) => {
      if (!active || !blob) return;
      objectUrl = URL.createObjectURL(blob);
      setCoverUrl(objectUrl);
    });

    return () => {
      active = false;
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
      }
    };
  }, [bookId, hasCover]);

  if (coverUrl) {
    return (
      <div className={`book-cover ${className}`}>
        <img src={coverUrl} alt={`Portada de ${title}`} loading="lazy" />
      </div>
    );
  }

  const [bgTop, bgBottom] = pickPalette(`${title}:${author}`);
  const initials = title
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase() || '')
    .join('');

  return (
    <div
      className={`book-cover book-cover-placeholder ${className}`}
      style={{
        background: `linear-gradient(155deg, ${bgTop}, ${bgBottom})`,
      }}
      aria-label={`Portada generada de ${title}`}
    >
      <span className="book-cover-initials">{initials || 'AL'}</span>
      <span className="book-cover-mini-title">{title}</span>
    </div>
  );
};
