package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.domain.audio.VisualEffectTier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ilustración animada liviana en Compose Canvas:
 * un libro abierto cuyas páginas pasan suavemente; parte de esas páginas se transforma en
 * notas musicales flotantes que se disuelven en ondas sonoras adaptadas al tamaño y capacidad del dispositivo.
 */
@Composable
fun BookToSoundCanvas(
  effectTier: VisualEffectTier,
  isAnimating: Boolean,
  readinessProgress: Float,
  modifier: Modifier = Modifier,
) {
  val runInfinite = isAnimating && effectTier != VisualEffectTier.STATIC_ACCESSIBLE

  val rawPhase =
    if (runInfinite) {
      val duration = if (effectTier == VisualEffectTier.REDUCED) 5200 else 3600
      val transition = rememberInfiniteTransition(label = "book_to_sound")
      val animated by
        transition.animateFloat(
          initialValue = 0f,
          targetValue = 1f,
          animationSpec =
            infiniteRepeatable(
              animation = tween(durationMillis = duration, easing = LinearEasing),
              repeatMode = RepeatMode.Restart,
            ),
          label = "phase",
        )
      animated
    } else {
      0.38f
    }

  val bookCoverColor = MaterialTheme.colorScheme.primary
  val pageFillColor = MaterialTheme.colorScheme.surface
  val pageEdgeColor = MaterialTheme.colorScheme.outline
  val pageAccentColor = MaterialTheme.colorScheme.secondary
  val noteColor = MaterialTheme.colorScheme.primary
  val waveColor = MaterialTheme.colorScheme.tertiary
  val readyGlowColor = MaterialTheme.colorScheme.secondaryContainer

  val reusableLeftPage = remember { Path() }
  val reusableRightPage = remember { Path() }
  val reusableTurningPage = remember { Path() }
  val reusableWavePath = remember { Path() }

  val clampedProgress = readinessProgress.coerceIn(0f, 1f)

  BoxWithConstraints(
    modifier =
      modifier
        .fillMaxWidth()
        .height(176.dp)
        .testTag("book_to_sound_canvas")
        .semantics {
          contentDescription =
            "Animación de libro abierto pasando páginas hacia notas musicales y ondas sonoras"
        },
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val w = size.width
      val h = size.height
      if (w <= 0f || h <= 0f) return@Canvas

      val scale = (minOf(w, h) / 180f).coerceIn(0.7f, 1.55f)
      val bookCenterX = w * 0.28f
      val bookBaseY = h * 0.68f
      val pageWidth = 48f * scale
      val pageHeight = 56f * scale

      // Halo sutil detrás del libro acorde al progreso del búfer inicial
      drawCircle(
        color = readyGlowColor.copy(alpha = 0.28f + 0.25f * clampedProgress),
        radius = (54f + 12f * clampedProgress) * scale,
        center = Offset(bookCenterX, bookBaseY - pageHeight * 0.45f),
      )

      // Cubierta inferior del libro abierto
      drawRoundRect(
        color = bookCoverColor.copy(alpha = 0.85f),
        topLeft = Offset(bookCenterX - pageWidth - 6f * scale, bookBaseY - 4f * scale),
        size = Size((pageWidth + 6f * scale) * 2f, 12f * scale),
        cornerRadius = CornerRadius(6f * scale, 6f * scale),
      )

      // Página izquierda abierta
      reusableLeftPage.reset()
      reusableLeftPage.moveTo(bookCenterX, bookBaseY)
      reusableLeftPage.quadraticTo(
        bookCenterX - pageWidth * 0.5f,
        bookBaseY - 8f * scale,
        bookCenterX - pageWidth,
        bookBaseY - 2f * scale,
      )
      reusableLeftPage.lineTo(bookCenterX - pageWidth, bookBaseY - pageHeight)
      reusableLeftPage.quadraticTo(
        bookCenterX - pageWidth * 0.5f,
        bookBaseY - pageHeight - 6f * scale,
        bookCenterX,
        bookBaseY - pageHeight + 4f * scale,
      )
      reusableLeftPage.close()
      drawPath(reusableLeftPage, color = pageFillColor)
      drawPath(
        reusableLeftPage,
        color = pageEdgeColor,
        style = Stroke(width = 2f * scale, join = StrokeJoin.Round),
      )

      // Página derecha abierta
      reusableRightPage.reset()
      reusableRightPage.moveTo(bookCenterX, bookBaseY)
      reusableRightPage.quadraticTo(
        bookCenterX + pageWidth * 0.5f,
        bookBaseY - 8f * scale,
        bookCenterX + pageWidth,
        bookBaseY - 2f * scale,
      )
      reusableRightPage.lineTo(bookCenterX + pageWidth, bookBaseY - pageHeight)
      reusableRightPage.quadraticTo(
        bookCenterX + pageWidth * 0.5f,
        bookBaseY - pageHeight - 6f * scale,
        bookCenterX,
        bookBaseY - pageHeight + 4f * scale,
      )
      reusableRightPage.close()
      drawPath(reusableRightPage, color = pageFillColor)
      drawPath(
        reusableRightPage,
        color = pageEdgeColor,
        style = Stroke(width = 2f * scale, join = StrokeJoin.Round),
      )

      // Hoja pasando suavemente de derecha a izquierda mediante arco Bezier
      val turnProgress =
        if (effectTier == VisualEffectTier.STATIC_ACCESSIBLE) 0.42f else rawPhase
      val turnAngle = turnProgress * PI.toFloat()
      val tipX = bookCenterX + pageWidth * 0.92f * cos(turnAngle)
      val tipY = bookBaseY - pageHeight - 18f * scale * sin(turnAngle)
      val midX = bookCenterX + (tipX - bookCenterX) * 0.55f
      val midY = tipY - 12f * scale * sin(turnAngle)

      reusableTurningPage.reset()
      reusableTurningPage.moveTo(bookCenterX, bookBaseY - 2f * scale)
      reusableTurningPage.quadraticTo(midX, tipY + pageHeight * 0.42f, tipX, tipY + pageHeight * 0.78f)
      reusableTurningPage.lineTo(tipX, tipY)
      reusableTurningPage.quadraticTo(
        midX,
        midY,
        bookCenterX,
        bookBaseY - pageHeight + 4f * scale,
      )
      reusableTurningPage.close()
      drawPath(reusableTurningPage, color = pageFillColor.copy(alpha = 0.94f))
      drawPath(
        reusableTurningPage,
        color = pageAccentColor,
        style = Stroke(width = 2f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
      )

      // Notas musicales desprendiéndose de la página en giro hacia el centro-derecha
      val noteCount = if (effectTier == VisualEffectTier.REDUCED) 2 else 3
      val startOriginX = bookCenterX + pageWidth * 0.35f
      val startOriginY = bookBaseY - pageHeight * 0.85f
      val waveStartX = w * 0.56f
      val waveEndX = w * 0.90f
      val centerY = h * 0.46f

      for (i in 0 until noteCount) {
        val t = (rawPhase + i.toFloat() / noteCount.toFloat()) % 1f
        val x = lerp(startOriginX, waveStartX + 8f * scale, t)
        val lift = sin(t * PI.toFloat()) * (28f + i * 7f) * scale
        val y = lerp(startOriginY, centerY + (i - 1) * 14f * scale, t) - lift
        val alpha = (sin(t * PI.toFloat()).coerceIn(0f, 1f)) * (0.55f + 0.45f * clampedProgress)
        val noteScale = scale * (0.72f + 0.32f * t)
        drawMusicalNoteGlyph(
          center = Offset(x, y),
          scale = noteScale,
          color = noteColor.copy(alpha = alpha.coerceIn(0.18f, 0.95f)),
          doubleFlag = i % 2 == 1,
        )
      }

      // Ondas sonoras continuas a la derecha emergiendo desde las notas
      val barCount = if (effectTier == VisualEffectTier.REDUCED) 6 else 9
      val barSpacing = ((waveEndX - waveStartX) / (barCount - 1).coerceAtLeast(1))
      for (b in 0 until barCount) {
        val bx = waveStartX + b * barSpacing
        val waveArg = (rawPhase * 2f * PI.toFloat()) - b * 0.55f
        val envelope = sin(((b + 0.5f) / barCount.toFloat()) * PI.toFloat())
        val amp =
          if (effectTier == VisualEffectTier.STATIC_ACCESSIBLE || !isAnimating) {
            16f * envelope * scale
          } else {
            (10f + 22f * ((sin(waveArg) + 1f) * 0.5f)) * envelope * (0.55f + 0.45f * clampedProgress) * scale
          }
        val barColor = if (b % 2 == 0) pageAccentColor else waveColor
        drawLine(
          color = barColor.copy(alpha = 0.85f),
          start = Offset(bx, centerY - amp),
          end = Offset(bx, centerY + amp),
          strokeWidth = 4.2f * scale,
          cap = StrokeCap.Round,
        )
      }

      // Línea ondulada envolvente conectando libro -> notas -> ondas
      reusableWavePath.reset()
      val curvePoints = 24
      for (p in 0..curvePoints) {
        val pt = p.toFloat() / curvePoints.toFloat()
        val px = lerp(startOriginX, waveEndX, pt)
        val waveY =
          centerY +
            sin(pt * 3f * PI.toFloat() - rawPhase * 2f * PI.toFloat()) *
            (8f * sin(pt * PI.toFloat()) * scale)
        if (p == 0) reusableWavePath.moveTo(px, waveY) else reusableWavePath.lineTo(px, waveY)
      }
      drawPath(
        reusableWavePath,
        color = waveColor.copy(alpha = 0.35f),
        style = Stroke(width = 2f * scale, cap = StrokeCap.Round),
      )
    }
  }
}

private fun DrawScope.drawMusicalNoteGlyph(
  center: Offset,
  scale: Float,
  color: Color,
  doubleFlag: Boolean,
) {
  val headRadiusX = 5.4f * scale
  val headRadiusY = 4.0f * scale
  drawOval(
    color = color,
    topLeft = Offset(center.x - headRadiusX, center.y - headRadiusY),
    size = Size(headRadiusX * 2f, headRadiusY * 2f),
  )
  val stemX = center.x + headRadiusX - 1f * scale
  val stemTopY = center.y - 18f * scale
  drawLine(
    color = color,
    start = Offset(stemX, center.y),
    end = Offset(stemX, stemTopY),
    strokeWidth = 2.2f * scale,
    cap = StrokeCap.Round,
  )
  if (doubleFlag) {
    val secondX = stemX + 10f * scale
    drawOval(
      color = color,
      topLeft = Offset(secondX - headRadiusX * 1.4f, center.y + 2f * scale - headRadiusY),
      size = Size(headRadiusX * 2f, headRadiusY * 2f),
    )
    drawLine(
      color = color,
      start = Offset(secondX, center.y + 2f * scale),
      end = Offset(secondX, stemTopY + 3f * scale),
      strokeWidth = 2.2f * scale,
      cap = StrokeCap.Round,
    )
    drawLine(
      color = color,
      start = Offset(stemX, stemTopY),
      end = Offset(secondX, stemTopY + 3f * scale),
      strokeWidth = 3f * scale,
      cap = StrokeCap.Round,
    )
  } else {
    drawLine(
      color = color,
      start = Offset(stemX, stemTopY),
      end = Offset(stemX + 7f * scale, stemTopY + 5f * scale),
      strokeWidth = 2.5f * scale,
      cap = StrokeCap.Round,
    )
  }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
  start + (stop - start) * fraction
