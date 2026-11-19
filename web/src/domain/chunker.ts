/**
 * Fragmentación determinista de capítulos en bloques narrables.
 * Réplica fiel de ChapterTextChunker.kt de Android.
 */

export const DEFAULT_MAX_CHARS_PER_CHUNK = 900;

export interface TextChunkSlice {
  index: number;
  text: string;
  charCount: number;
}

/**
 * Divide un capítulo en fragmentos naturales respetando párrafos, oraciones y límites
 * de longitud para mantener latencia inicial baja y prosodia natural en Piper VITS.
 */
export function splitChapterIntoChunks(
  rawText: string,
  maxCharsPerChunk: number = DEFAULT_MAX_CHARS_PER_CHUNK,
): TextChunkSlice[] {
  const normalized = rawText
    .replace(/\r\n/g, '\n')
    .replace(/\r/g, '\n')
    .trim();

  if (!normalized) return [];

  const maxChars = Math.max(180, maxCharsPerChunk);
  const paragraphs = normalized
    .split(/\n{2,}/)
    .map((p) => p.replace(/\s+/g, ' ').trim())
    .filter((p) => p.length > 0);

  const chunks: string[] = [];
  let current = '';

  const flushCurrent = () => {
    const trimmed = current.trim();
    if (trimmed.length > 0) {
      chunks.push(trimmed);
    }
    current = '';
  };

  for (const paragraph of paragraphs) {
    if (paragraph.length <= maxChars) {
      if (!current) {
        current = paragraph;
      } else if (current.length + 2 + paragraph.length <= maxChars) {
        current = `${current}\n\n${paragraph}`;
      } else {
        flushCurrent();
        current = paragraph;
      }
      continue;
    }

    // Párrafo largo: dividir por oraciones conservando puntuación final.
    const sentences = splitIntoSentences(paragraph);
    for (const sentence of sentences) {
      if (sentence.length <= maxChars) {
        if (!current) {
          current = sentence;
        } else if (current.length + 1 + sentence.length <= maxChars) {
          current = `${current} ${sentence}`;
        } else {
          flushCurrent();
          current = sentence;
        }
      } else {
        flushCurrent();
        const subSlices = splitLongSentenceByWords(sentence, maxChars);
        for (const sub of subSlices) {
          chunks.push(sub);
        }
      }
    }
  }

  flushCurrent();

  return chunks.map((text, index) => ({
    index,
    text,
    charCount: text.length,
  }));
}

function splitIntoSentences(paragraph: string): string[] {
  const matches = paragraph.match(/[^.!?…]+[.!?…]+["»”)]*|[^.!?…]+$/g);
  if (!matches) return [paragraph];
  return matches.map((s) => s.trim()).filter((s) => s.length > 0);
}

function splitLongSentenceByWords(sentence: string, maxChars: number): string[] {
  const words = sentence.split(/\s+/).filter(Boolean);
  const result: string[] = [];
  let buffer = '';

  for (const word of words) {
    if (word.length > maxChars) {
      if (buffer) {
        result.push(buffer);
        buffer = '';
      }
      for (let i = 0; i < word.length; i += maxChars) {
        result.push(word.slice(i, i + maxChars));
      }
      continue;
    }

    if (!buffer) {
      buffer = word;
    } else if (buffer.length + 1 + word.length <= maxChars) {
      buffer = `${buffer} ${word}`;
    } else {
      result.push(buffer);
      buffer = word;
    }
  }

  if (buffer) {
    result.push(buffer);
  }
  return result;
}
