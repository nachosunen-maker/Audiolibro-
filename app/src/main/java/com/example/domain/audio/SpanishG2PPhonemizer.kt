package com.example.domain.audio

import java.util.regex.Pattern

/**
 * Fonemizador G2P (Grapheme-to-Phoneme) para español de España (Castellano),
 * diseñado específicamente para el modelo Piper VITS "es_ES-sharvard-medium"
 * y su integración con sherpa-onnx 2.0.1.
 *
 * Mapea el texto en español a los símbolos fonéticos IPA exactos definidos en
 * `tokens.txt` del modelo SHARVARD:
 * - Varianza consonántica: θ (c/z), ɲ (ñ), ʝ (ll/y), t+ʃ (ch), ɾ (r simple),
 *   r (rr/r inicial), β (b/v suave), ð (d suave), ɣ (g suave), ɡ (g oclusiva), x (j/g).
 * - Verbalización completa de números a palabras en español.
 * - Tratamiento de signos de puntuación (. , : ; ! ? -).
 * - Delimitadores de frase: '^' al inicio y '$' al final.
 */
object SpanishG2PPhonemizer {

  private val DIGIT_PATTERN = Pattern.compile("\\b\\d+\\b")

  private val UNITS = arrayOf("", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve")
  private val TEENS = arrayOf("diez", "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve")
  private val TENS = arrayOf("", "diez", "veinte", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa")
  private val HUNDREDS = arrayOf("", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos", "seiscientos", "setecientos", "ochocientos", "novecientos")

  fun spellSpanishNumber(n: Long): String {
    if (n == 0L) return "cero"
    if (n < 0L) return "menos " + spellSpanishNumber(-n)
    if (n < 1000L) return spellUnderThousand(n.toInt())
    if (n < 1_000_000L) {
      val thousands = (n / 1000L).toInt()
      val rem = (n % 1000L).toInt()
      val thStr = if (thousands == 1) "mil" else "${spellUnderThousand(thousands)} mil"
      val remStr = if (rem > 0) " " + spellUnderThousand(rem) else ""
      return "$thStr$remStr".trim()
    }
    if (n < 1_000_000_000L) {
      val millions = (n / 1_000_000L).toInt()
      val rem = (n % 1_000_000L)
      val milStr = if (millions == 1) "un millón" else "${spellUnderThousand(millions)} millones"
      val remStr = if (rem > 0L) " " + spellSpanishNumber(rem) else ""
      return "$milStr$remStr".trim()
    }
    return n.toString()
  }

  private fun spellUnderThousand(num: Int): String {
    if (num == 0) return ""
    if (num == 100) return "cien"
    val sb = StringBuilder()
    val c = num / 100
    val rem = num % 100
    if (c > 0) {
      sb.append(HUNDREDS[c])
    }
    if (rem in 10..19) {
      if (sb.isNotEmpty()) sb.append(' ')
      sb.append(TEENS[rem - 10])
    } else if (rem in 20..29) {
      if (sb.isNotEmpty()) sb.append(' ')
      if (rem == 20) sb.append("veinte")
      else sb.append("veinti").append(UNITS[rem - 20])
    } else if (rem >= 30) {
      val t = rem / 10
      val u = rem % 10
      if (sb.isNotEmpty()) sb.append(' ')
      if (u == 0) sb.append(TENS[t])
      else sb.append(TENS[t]).append(" y ").append(UNITS[u])
    } else if (rem > 0) {
      if (sb.isNotEmpty()) sb.append(' ')
      sb.append(UNITS[rem])
    }
    return sb.toString().trim()
  }

  fun verbalizeNumbers(text: String): String {
    val matcher = DIGIT_PATTERN.matcher(text)
    val sb = StringBuffer()
    while (matcher.find()) {
      val numStr = matcher.group()
      val num = numStr.toLongOrNull()
      val spelled = if (num != null) spellSpanishNumber(num) else numStr
      matcher.appendReplacement(sb, " $spelled ")
    }
    matcher.appendTail(sb)
    return sb.toString()
  }

  /**
   * Convierte un texto en español a una lista de símbolos fonéticos IPA
   * exactos compatibles con `tokens.txt` de Piper SHARVARD.
   */
  fun phonemizeToTokens(rawText: String): List<String> {
    if (rawText.isBlank()) return emptyList()

    val verbalized = verbalizeNumbers(rawText)

    var t = verbalized.lowercase()
    t = t.replace(Regex("[¿¡]"), "")
    t = t.replace(Regex("[«»\"“”]"), "")
    t = t.replace(Regex("[—–]"), ", ")
    t = t.replace(Regex("\\.\\.\\.|…"), ". ")

    val vowels = setOf('a', 'e', 'i', 'o', 'u', 'á', 'é', 'í', 'ó', 'ú', 'ü')
    val tokens = mutableListOf<String>()
    tokens.add("^")

    var i = 0
    val n = t.length

    while (i < n) {
      val c = t[i]
      val prevC = if (i > 0) t[i - 1] else ' '
      val nextC = if (i + 1 < n) t[i + 1] else ' '
      val nextNext = if (i + 2 < n) t[i + 2] else ' '

      // Puntuación y espacios
      if (c in " .,!?:;-") {
        if (c == ' ' && (tokens.isEmpty() || tokens.last() == " " || tokens.last() == "^")) {
          i++
          continue
        }
        tokens.add(c.toString())
        i++
        continue
      }

      // Vocales
      if (c == 'a' || c == 'á') {
        tokens.add("a")
        i++
        continue
      }
      if (c == 'e' || c == 'é') {
        tokens.add("e")
        i++
        continue
      }
      if (c == 'i' || c == 'í') {
        tokens.add("i")
        i++
        continue
      }
      if (c == 'o' || c == 'ó') {
        tokens.add("o")
        i++
        continue
      }
      if (c == 'u' || c == 'ú' || c == 'ü') {
        tokens.add("u")
        i++
        continue
      }

      // 'ch' -> t + ʃ
      if (c == 'c' && nextC == 'h') {
        tokens.add("t")
        tokens.add("ʃ")
        i += 2
        continue
      }

      // 'c' ante e/i -> θ, ante a/o/u -> k
      if (c == 'c') {
        if (nextC in "eiéí") {
          tokens.add("θ")
        } else {
          tokens.add("k")
        }
        i++
        continue
      }

      // 'z' -> θ
      if (c == 'z') {
        tokens.add("θ")
        i++
        continue
      }

      // 'qu' -> k (u muda)
      if (c == 'q' && nextC == 'u') {
        tokens.add("k")
        i += 2
        continue
      }

      // 'gu' ante e/i -> g/ɣ (u muda)
      if (c == 'g' && nextC == 'u' && nextNext in "eiéí") {
        val isIntervocalic = prevC in vowels
        tokens.add(if (isIntervocalic) "ɣ" else "\u0261")
        i += 2
        continue
      }

      // 'g' ante e/i -> x, oclusiva/fricativa ante a/o/u
      if (c == 'g') {
        if (nextC in "eiéí") {
          tokens.add("x")
        } else if (prevC in vowels && nextC in vowels) {
          tokens.add("ɣ")
        } else {
          tokens.add("\u0261")
        }
        i++
        continue
      }

      // 'j' -> x
      if (c == 'j') {
        tokens.add("x")
        i++
        continue
      }

      // 'h' (muda)
      if (c == 'h') {
        i++
        continue
      }

      // 'll' -> ʝ
      if (c == 'l' && nextC == 'l') {
        tokens.add("ʝ")
        i += 2
        continue
      }

      // 'y' ante vocal -> ʝ, como vocal o final -> i
      if (c == 'y') {
        if (nextC in vowels) {
          tokens.add("ʝ")
        } else {
          tokens.add("i")
        }
        i++
        continue
      }

      // 'ñ' -> ɲ
      if (c == 'ñ') {
        tokens.add("ɲ")
        i++
        continue
      }

      // 'rr' -> r vibrante
      if (c == 'r' && nextC == 'r') {
        tokens.add("r")
        i += 2
        continue
      }

      // 'r' -> r vibrante inicial/tras n,l,s; ɾ simple en otros casos
      if (c == 'r') {
        if (prevC in " \t\n.,!?:;-" || prevC in "nls") {
          tokens.add("r")
        } else {
          tokens.add("ɾ")
        }
        i++
        continue
      }

      // 'b' / 'v' -> β entre vocales, b en otros
      if (c == 'b' || c == 'v') {
        if (prevC in vowels && nextC in vowels) {
          tokens.add("β")
        } else {
          tokens.add("b")
        }
        i++
        continue
      }

      // 'd' -> ð entre vocales o final, d en otros
      if (c == 'd') {
        if (prevC in vowels && (nextC in vowels || nextC == ' ')) {
          tokens.add("ð")
        } else {
          tokens.add("d")
        }
        i++
        continue
      }

      // 'x' -> k + s
      if (c == 'x') {
        tokens.add("k")
        tokens.add("s")
        i++
        continue
      }

      // Consonantes regulares
      if (c in "ptkfsmnlw") {
        tokens.add(c.toString())
        i++
        continue
      }

      i++
    }

    tokens.add("$")
    return tokens
  }

  fun tokensToCodepoints(tokens: List<String>): IntArray {
    return tokens.map { it.codePointAt(0) }.toIntArray()
  }
}
