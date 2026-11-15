package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.model.ProgressiveChapterPhase

/**
 * Barra multicapa basada exclusivamente en datos reales de síntesis y reproducción:
 * - Pista de fondo: total real del capítulo aún pendiente.
 * - Capa preparada (secondary): fracción ya generada y verificada en disco.
 * - Capa activa (tertiary): fragmento actualmente sintetizándose por adelantado.
 * - Capa reproducida (primary): progreso reproducido sobre el audio ya preparado.
 */
@Composable
fun ProgressiveSegmentedBar(
  playedFraction: Float,
  preparedFraction: Float,
  isGeneratingAhead: Boolean,
  isPaused: Boolean,
  isError: Boolean,
  totalChunks: Int,
  modifier: Modifier = Modifier,
) {
  val safePrepared = preparedFraction.coerceIn(0f, 1f)
  val safePlayed = playedFraction.coerceIn(0f, safePrepared)
  val activeChunkSlice =
    if (isGeneratingAhead && safePrepared < 1f && totalChunks > 0) {
      (safePrepared + (1f / totalChunks.coerceAtLeast(1))).coerceAtMost(1f)
    } else {
      safePrepared
    }

  val trackColor = MaterialTheme.colorScheme.surfaceVariant
  val preparedColor =
    when {
      isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
      isPaused -> MaterialTheme.colorScheme.outline
      else -> MaterialTheme.colorScheme.secondary
    }
  val aheadColor = MaterialTheme.colorScheme.tertiary
  val playedColor = MaterialTheme.colorScheme.primary

  Canvas(
    modifier =
      modifier
        .fillMaxWidth()
        .height(10.dp)
        .clip(RoundedCornerShape(5.dp))
        .testTag("progressive_segmented_bar")
        .semantics {
          contentDescription = "Progreso real de preparación y reproducción del capítulo"
        }
  ) {
    val w = size.width
    val h = size.height
    val radius = CornerRadius(h / 2f, h / 2f)

    // Base pendiente
    drawRoundRect(color = trackColor, size = Size(w, h), cornerRadius = radius)

    // Fragmento en curso por adelantado
    if (activeChunkSlice > safePrepared) {
      drawRoundRect(
        color = aheadColor.copy(alpha = 0.72f),
        size = Size(w * activeChunkSlice, h),
        cornerRadius = radius,
      )
    }

    // Audio preparado y verificado
    if (safePrepared > 0f) {
      drawRoundRect(
        color = preparedColor,
        size = Size(w * safePrepared, h),
        cornerRadius = radius,
      )
    }

    // Audio ya reproducido
    if (safePlayed > 0f) {
      drawRoundRect(
        color = playedColor,
        topLeft = Offset.Zero,
        size = Size(w * safePlayed, h),
        cornerRadius = radius,
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProgressiveStateLegend(
  phase: ProgressiveChapterPhase,
  canStartPlayback: Boolean,
  isPlaying: Boolean,
  isSynthesizing: Boolean,
  modifier: Modifier = Modifier,
) {
  val preparedActive = canStartPlayback || phase == ProgressiveChapterPhase.COMPLETED
  val playingActive = isPlaying
  val lookaheadActive = isSynthesizing && !isPlaying && phase != ProgressiveChapterPhase.RECOVERABLE_ERROR ||
    (isSynthesizing && isPlaying)
  val pausedActive = phase == ProgressiveChapterPhase.PAUSED
  val errorActive = phase == ProgressiveChapterPhase.RECOVERABLE_ERROR

  FlowRow(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    StateChip(
      label = "Audio preparado",
      dotColor = MaterialTheme.colorScheme.secondary,
      active = preparedActive,
      tag = "state_chip_prepared",
    )
    StateChip(
      label = "Reproduciendo",
      dotColor = MaterialTheme.colorScheme.primary,
      active = playingActive,
      tag = "state_chip_playing",
    )
    StateChip(
      label = "Por adelantado",
      dotColor = MaterialTheme.colorScheme.tertiary,
      active = lookaheadActive,
      tag = "state_chip_lookahead",
    )
    StateChip(
      label = "En pausa",
      dotColor = MaterialTheme.colorScheme.outline,
      active = pausedActive,
      tag = "state_chip_paused",
    )
    StateChip(
      label = "Error recuperable",
      dotColor = MaterialTheme.colorScheme.error,
      active = errorActive,
      tag = "state_chip_error",
    )
  }
}

@Composable
private fun StateChip(
  label: String,
  dotColor: Color,
  active: Boolean,
  tag: String,
) {
  val bg =
    if (active) dotColor.copy(alpha = 0.14f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
  val border =
    if (active) BorderStroke(1.dp, dotColor.copy(alpha = 0.7f))
    else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
  val textColor =
    if (active) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurfaceVariant

  Surface(
    modifier = Modifier.testTag(tag),
    shape = RoundedCornerShape(10.dp),
    color = bg,
    border = border,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (active) dotColor else dotColor.copy(alpha = 0.45f))
      )
      Text(
        text = label,
        style =
          MaterialTheme.typography.labelSmall.copy(
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
          ),
        color = textColor,
      )
    }
  }
}
