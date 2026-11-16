package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.audio.ProgressiveChapterState
import com.example.domain.audio.VisualEffectTier
import com.example.domain.model.NarrationEstimator
import com.example.domain.model.ProgressiveChapterPhase
import com.example.ui.components.BookToSoundCanvas
import com.example.ui.components.PlaybackAvailabilityBar
import com.example.ui.components.ProgressiveSegmentedBar
import com.example.ui.components.ProgressiveStateLegend
import com.example.ui.viewmodel.ProgressiveChapterViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressiveChapterScreen(
  viewModel: ProgressiveChapterViewModel,
  onNavigateBack: () -> Unit,
  onOpenPlayer: (bookId: String, chapterIndex: Int) -> Unit,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()

  BackHandler { onNavigateBack() }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    contentWindowInsets = WindowInsets.safeDrawing,
    topBar = {
      TopAppBar(
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
          ),
        navigationIcon = {
          IconButton(
            onClick = onNavigateBack,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("progressive_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
              contentDescription = stringResource(R.string.action_back),
            )
          }
        },
        title = {
          Column {
            Text(
              text = stringResource(R.string.progressive_loading_title),
              style = MaterialTheme.typography.titleLarge,
              modifier = Modifier.testTag("progressive_loading_title"),
            )
            val current = state
            if (current != null && current.bookTitle.isNotBlank()) {
              Text(
                text = current.bookTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        },
      )
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      val current = state
      if (current == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      } else {
        ProgressiveChapterContent(
          state = current,
          isActivelyPlayingThisChapter =
            playbackState.isPlaying &&
              playbackState.activeBookId == current.bookId &&
              playbackState.currentChapterIndex == current.chapterIndex,
          onPlay = {
            viewModel.playReadyChapter {
              onOpenPlayer(current.bookId, current.chapterIndex)
            }
          },
          onPause = viewModel::pauseGeneration,
          onResumeOrRetry = viewModel::resumeOrRetryGeneration,
        )
      }
    }
  }
}

@Composable
internal fun ProgressiveChapterContent(
  state: ProgressiveChapterState,
  isActivelyPlayingThisChapter: Boolean,
  onPlay: () -> Unit,
  onPause: () -> Unit,
  onResumeOrRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val scrollState = rememberScrollState()
  val isAnimatingCanvas =
    (state.isSynthesizing || isActivelyPlayingThisChapter) &&
      state.phase != ProgressiveChapterPhase.PAUSED &&
      state.phase != ProgressiveChapterPhase.RECOVERABLE_ERROR

  Column(
    modifier =
      modifier
        .fillMaxSize()
        .widthIn(max = 760.dp)
        .verticalScroll(scrollState)
        .padding(horizontal = 20.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    // Cabecera de capítulo
    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
      Column(
        modifier = Modifier.padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = MaterialTheme.colorScheme.primaryContainer,
        ) {
          Text(
            text = "Capítulo ${state.chapterNumber} de ${state.totalChapters}",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier =
              Modifier
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .testTag("progressive_chapter_badge"),
          )
        }
        Text(
          text = state.chapterTitle.ifBlank { "Capítulo ${state.chapterIndex}" },
          style = MaterialTheme.typography.headlineSmall,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.testTag("progressive_chapter_title"),
        )
      }
    }

    // Ilustración animada libro -> notas -> ondas sonoras
    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        BookToSoundCanvas(
          effectTier = state.deviceProfile.recommendedEffectTier,
          isAnimating = isAnimatingCanvas,
          readinessProgress = state.initialReadinessFraction,
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text =
              when (state.deviceProfile.recommendedEffectTier) {
                VisualEffectTier.STATIC_ACCESSIBLE -> "Modo sin movimiento (accesibilidad)"
                VisualEffectTier.REDUCED -> "Efectos reducidos por ahorro o RAM"
                VisualEffectTier.FULL -> "Conversión neuronal local sin conexión"
              },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (state.measuredRtf > 0f) {
            Text(
              text = String.format(Locale.US, "RTF %.2fx", state.measuredRtf),
              style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
      }
    }

    // Panel de progreso real y búfer adaptativo
    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
      Column(
        modifier = Modifier.padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Progreso de generación del capítulo",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = state.statusMessage.ifBlank { "Preparando voz local" },
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
              color =
                if (state.phase == ProgressiveChapterPhase.RECOVERABLE_ERROR) {
                  MaterialTheme.colorScheme.error
                } else {
                  MaterialTheme.colorScheme.primary
                },
              modifier = Modifier.testTag("progressive_status_text"),
            )
          }
          Text(
            text =
              if (state.canStartPlayback) {
                "${state.chapterPercent} %"
              } else {
                "Inicio ${state.initialReadinessPercent} %"
              },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("progressive_percent_text"),
          )
        }

        ProgressiveSegmentedBar(
          playedFraction = 0f,
          preparedFraction = state.overallChapterProgressFraction,
          isGeneratingAhead = state.isSynthesizing,
          isPaused = state.phase == ProgressiveChapterPhase.PAUSED,
          isError = state.phase == ProgressiveChapterPhase.RECOVERABLE_ERROR,
          totalChunks = state.totalChunks,
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text =
              if (state.totalChunks > 0) {
                "${state.chunksCompleted}/${state.totalChunks} fragmentos (${state.chapterPercent} % de texto sintetizado)"
              } else {
                "Calculando fragmentos del capítulo…"
              },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text =
              if (state.bufferedDurationMs > 0L) {
                "${NarrationEstimator.formatPlaybackTime(state.bufferedDurationMs)} listos"
              } else {
                "00:00 listos"
              },
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
          )
        }

        PlaybackAvailabilityBar(
          playedDurationMs = state.playedDurationMs,
          bufferedDurationMs = state.bufferedDurationMs,
          aheadDurationMs = state.aheadDurationMs,
          isWaitingForBuffer = false,
        )

        if (state.initialReadyTargetMs > 0L && !state.isCompleteChapterReady) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Outlined.Speed,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text =
                "Objetivo de inicio adaptable: ${NarrationEstimator.formatPlaybackTime(state.initialReadyTargetMs)}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        Text(
          text = stringResource(R.string.progressive_ahead_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ProgressiveStateLegend(
          phase = state.phase,
          canStartPlayback = state.canStartPlayback,
          isPlaying = isActivelyPlayingThisChapter,
          isSynthesizing = state.isSynthesizing,
        )
      }
    }

    // Error recuperable con fragmentos intactos
    if (!state.errorMessage.isNullOrBlank() && state.phase == ProgressiveChapterPhase.RECOVERABLE_ERROR) {
      Surface(
        modifier = Modifier.fillMaxWidth().testTag("progressive_error_banner"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
      ) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = "Error recuperable · ${state.chunksCompleted} fragmentos guardados",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onErrorContainer,
          )
          Text(
            text = state.errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
          )
        }
      }
    }

    // Controles de acción
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!state.isCompleteChapterReady) {
        when (state.phase) {
          ProgressiveChapterPhase.PAUSED -> {
            OutlinedButton(
              onClick = onResumeOrRetry,
              modifier =
                Modifier
                  .weight(1f)
                  .height(52.dp)
                  .testTag("progressive_pause_resume_button"),
              shape = MaterialTheme.shapes.medium,
            ) {
              Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null)
              Spacer(modifier = Modifier.width(6.dp))
              Text("Reanudar")
            }
          }
          ProgressiveChapterPhase.RECOVERABLE_ERROR -> {
            OutlinedButton(
              onClick = onResumeOrRetry,
              modifier =
                Modifier
                  .weight(1f)
                  .height(52.dp)
                  .testTag("progressive_retry_button"),
              shape = MaterialTheme.shapes.medium,
            ) {
              Icon(imageVector = Icons.Outlined.Refresh, contentDescription = null)
              Spacer(modifier = Modifier.width(6.dp))
              Text("Reintentar")
            }
          }
          else -> {
            OutlinedButton(
              onClick = onPause,
              modifier =
                Modifier
                  .weight(1f)
                  .height(52.dp)
                  .testTag("progressive_pause_resume_button"),
              shape = MaterialTheme.shapes.medium,
            ) {
              Icon(imageVector = Icons.Outlined.Pause, contentDescription = null)
              Spacer(modifier = Modifier.width(6.dp))
              Text("Pausar")
            }
          }
        }
      }

      Button(
        onClick = onPlay,
        enabled = state.canStartPlayback,
        modifier =
          Modifier
            .weight(1f)
            .height(52.dp)
            .testTag("progressive_play_button"),
        shape = MaterialTheme.shapes.medium,
      ) {
        Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null)
        Spacer(modifier = Modifier.width(6.dp))
        Text("Reproducir", style = MaterialTheme.typography.labelLarge)
      }
    }

    Spacer(modifier = Modifier.height(12.dp))
  }
}
