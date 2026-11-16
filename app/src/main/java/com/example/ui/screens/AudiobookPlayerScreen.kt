package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.audio.ProgressiveChapterState
import com.example.domain.model.AudioGenerationStatus
import com.example.domain.model.Chapter
import com.example.domain.model.NarrationEstimator
import com.example.domain.model.ProgressiveChapterPhase
import com.example.player.PlaybackUiState
import com.example.ui.components.BookCover
import com.example.ui.components.PlaybackAvailabilityBar
import com.example.ui.components.ProgressiveSegmentedBar
import com.example.ui.components.ProgressiveStateLegend
import com.example.ui.viewmodel.PlayerViewModel
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AudiobookPlayerScreen(
  viewModel: PlayerViewModel,
  onNavigateBack: () -> Unit,
) {
  val bookWithChapters by viewModel.bookWithChapters.collectAsStateWithLifecycle()
  val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
  val progressiveState by viewModel.progressiveState.collectAsStateWithLifecycle()
  val context = LocalContext.current

  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

  LaunchedEffect(Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val granted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
      if (!granted) {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }
  }

  val book = bookWithChapters?.book
  val playableChapters =
    remember(bookWithChapters, playbackState.currentChapterIndex) {
      bookWithChapters?.chapters?.filter { ch ->
        (ch.audioStatus == AudioGenerationStatus.READY && !ch.audioFilePath.isNullOrBlank()) ||
          ch.generatedChunks > 0 ||
          (playbackState.isProgressiveActive && ch.chapterIndex == playbackState.currentChapterIndex)
      } ?: emptyList()
    }

  var isScrubbing by remember { mutableStateOf(false) }
  var scrubPositionMs by remember { mutableFloatStateOf(0f) }

  val totalDurationMs = playbackState.durationMs.coerceAtLeast(1L)
  val displayedPositionMs =
    if (isScrubbing) scrubPositionMs.toLong() else playbackState.positionMs.coerceIn(0L, totalDurationMs)

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
            modifier = Modifier.minimumInteractiveComponentSize().testTag("player_back_button"),
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
              text = "Reproductor",
              style = MaterialTheme.typography.titleLarge,
            )
            if (book != null) {
              Text(
                text = "Voz ${book.selectedVoice.displayName.lowercase()} · Local offline (ID ${book.selectedVoice.speakerId})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
      if (playableChapters.isEmpty()) {
        Column(
          modifier = Modifier.fillMaxSize().padding(24.dp),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Text(
            text = "Todavía no hay capítulos de audio generados para reproducir.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
          contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          item {
            Column(
              modifier = Modifier.fillMaxWidth(),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              BookCover(
                title = book?.title ?: playbackState.activeBookTitle,
                author = book?.author ?: playbackState.activeAuthor,
                coverPath = book?.coverImagePath ?: playbackState.activeCoverPath,
                modifier = Modifier.width(148.dp).height(214.dp),
              )

              Text(
                text = book?.title ?: playbackState.activeBookTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
              )

              Text(
                text =
                  playbackState.currentChapterTitle.ifBlank {
                    playableChapters.firstOrNull()?.title.orEmpty()
                  },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
              )
            }
          }

          if (!playbackState.errorMessage.isNullOrBlank()) {
            item {
              Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
              ) {
                Text(
                  text = playbackState.errorMessage!!,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  modifier = Modifier.padding(12.dp),
                )
              }
            }
          }

          item {
            Column(modifier = Modifier.fillMaxWidth()) {
              Slider(
                value = displayedPositionMs.toFloat(),
                onValueChange = { value ->
                  isScrubbing = true
                  scrubPositionMs = value
                },
                onValueChangeFinished = {
                  viewModel.seekTo(scrubPositionMs.toLong())
                  isScrubbing = false
                },
                valueRange = 0f..totalDurationMs.toFloat(),
                modifier = Modifier.fillMaxWidth().testTag("player_seek_slider"),
              )
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text(
                  text = NarrationEstimator.formatPlaybackTime(displayedPositionMs),
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                  text = NarrationEstimator.formatPlaybackTime(playbackState.durationMs),
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }

          item {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceEvenly,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              IconButton(
                onClick = viewModel::previousChapter,
                modifier = Modifier.size(52.dp).testTag("player_prev_chapter"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.SkipPrevious,
                  contentDescription = "Capítulo anterior",
                  modifier = Modifier.size(32.dp),
                )
              }

              IconButton(
                onClick = viewModel::seekBack,
                modifier = Modifier.size(52.dp).testTag("player_rewind"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Replay10,
                  contentDescription = "Retroceder 15 segundos",
                  modifier = Modifier.size(30.dp),
                )
              }

              FilledIconButton(
                onClick = viewModel::togglePlayPause,
                modifier = Modifier.size(68.dp).testTag("player_play_pause"),
                shape = CircleShape,
                colors =
                  IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                  ),
              ) {
                Icon(
                  imageVector =
                    if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                  contentDescription = if (playbackState.isPlaying) "Pausar" else "Reproducir",
                  modifier = Modifier.size(36.dp),
                )
              }

              IconButton(
                onClick = viewModel::seekForward,
                modifier = Modifier.size(52.dp).testTag("player_forward"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Forward10,
                  contentDescription = "Avanzar 15 segundos",
                  modifier = Modifier.size(30.dp),
                )
              }

              IconButton(
                onClick = viewModel::nextChapter,
                modifier = Modifier.size(52.dp).testTag("player_next_chapter"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.SkipNext,
                  contentDescription = "Siguiente capítulo",
                  modifier = Modifier.size(32.dp),
                )
              }
            }
          }

          item {
            val speedOptions = listOf(0.85f, 1.0f, 1.25f, 1.5f)
            FlowRow(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              speedOptions.forEach { spd ->
                val selected = abs(playbackState.playbackSpeed - spd) < 0.03f
                FilterChip(
                  selected = selected,
                  onClick = { viewModel.setPlaybackSpeed(spd) },
                  label = { Text("${spd}x") },
                  modifier = Modifier.testTag("player_speed_${spd}x"),
                )
              }
            }
          }

          val matchingProgressive =
            progressiveState?.takeIf {
              it.bookId == playbackState.activeBookId &&
                it.chapterIndex == playbackState.currentChapterIndex
            }
          if (playbackState.isProgressiveActive ||
            (matchingProgressive != null && !matchingProgressive.isCompleteChapterReady)
          ) {
            item {
              ProgressiveBufferPlayerCard(
                playbackState = playbackState,
                progressiveState = matchingProgressive,
                onPauseLookahead = viewModel::pauseLookaheadGeneration,
                onResumeLookahead = viewModel::resumeLookaheadGeneration,
              )
            }
          }

          item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "Capítulos disponibles sin conexión (${playableChapters.size})",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onBackground,
            )
          }

          items(playableChapters, key = { it.id }) { chapter ->
            val isCurrent = playbackState.currentChapterIndex == chapter.chapterIndex
            PlayableChapterRow(
              chapter = chapter,
              isCurrent = isCurrent,
              onClick = { viewModel.jumpToChapter(chapter.chapterIndex) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun PlayableChapterRow(
  chapter: Chapter,
  isCurrent: Boolean,
  onClick: () -> Unit,
) {
  Card(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().testTag("player_chapter_row_${chapter.chapterIndex}"),
    shape = MaterialTheme.shapes.medium,
    colors =
      CardDefaults.cardColors(
        containerColor =
          if (isCurrent) MaterialTheme.colorScheme.primaryContainer
          else MaterialTheme.colorScheme.surface,
      ),
    border =
      BorderStroke(
        width = if (isCurrent) 2.dp else 1.dp,
        color =
          if (isCurrent) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.outlineVariant,
      ),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        modifier = Modifier.size(36.dp),
        shape = RoundedCornerShape(8.dp),
        color =
          if (isCurrent) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.surfaceVariant,
      ) {
        Box(contentAlignment = Alignment.Center) {
          if (isCurrent) {
            Icon(
              imageVector = Icons.Rounded.GraphicEq,
              contentDescription = "Reproduciendo",
              tint = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.size(20.dp),
            )
          } else {
            Text(
              text = chapter.chapterIndex.toString().padStart(2, '0'),
              style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
      }

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = chapter.title,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text =
            if (chapter.audioStatus == AudioGenerationStatus.READY) {
              "${NarrationEstimator.formatPlaybackTime(chapter.audioDurationMs)} · ${NarrationEstimator.formatFileSize(chapter.audioSizeBytes)}"
            } else {
              "Carga progresiva · ${chapter.generatedChunks}/${chapter.totalChunks.coerceAtLeast(1)} fragmentos"
            },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun ProgressiveBufferPlayerCard(
  playbackState: PlaybackUiState,
  progressiveState: ProgressiveChapterState?,
  onPauseLookahead: () -> Unit,
  onResumeLookahead: () -> Unit,
) {
  val phase = progressiveState?.phase ?: ProgressiveChapterPhase.PLAYING_LOOKAHEAD
  val preparedFraction =
    progressiveState?.overallChapterProgressFraction
      ?: if (playbackState.durationMs > 0L) {
        (playbackState.bufferedDurationMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
      } else {
        1f
      }
  val synthPercent =
    progressiveState?.chapterPercent
      ?: (preparedFraction * 100f).toInt().coerceIn(0, 100)

  Surface(
    modifier = Modifier.fillMaxWidth().testTag("player_progressive_buffer_card"),
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text =
              when {
                playbackState.isWaitingForBuffer -> "Esperando siguiente fragmento local…"
                progressiveState != null && progressiveState.isSynthesizing ->
                  progressiveState.statusMessage.ifBlank { "Generando audio por adelantado…" }
                phase == ProgressiveChapterPhase.PAUSED -> "Generación por adelantado en pausa"
                phase == ProgressiveChapterPhase.RECOVERABLE_ERROR ->
                  "Error recuperable en búfer · pulsa Reintentar"
                else -> "Audio preparado · reproducción progresiva"
              },
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color =
              if (phase == ProgressiveChapterPhase.RECOVERABLE_ERROR) {
                MaterialTheme.colorScheme.error
              } else {
                MaterialTheme.colorScheme.primary
              },
            modifier = Modifier.testTag("player_progressive_status_text"),
          )
          Text(
            text =
              "Búfer adelantado: ${NarrationEstimator.formatPlaybackTime(playbackState.aheadBufferedDurationMs)} · Listos ${progressiveState?.chunksCompleted ?: 0}/${progressiveState?.totalChunks ?: 0}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        if (progressiveState?.isCompleteChapterReady != true) {
          when (phase) {
            ProgressiveChapterPhase.PAUSED -> {
              TextButton(
                onClick = onResumeLookahead,
                modifier = Modifier.testTag("player_resume_lookahead_button"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.PlayArrow,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Reanudar")
              }
            }
            ProgressiveChapterPhase.RECOVERABLE_ERROR -> {
              TextButton(
                onClick = onResumeLookahead,
                modifier = Modifier.testTag("player_retry_lookahead_button"),
              ) {
                Icon(
                  imageVector = Icons.Outlined.Refresh,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Reintentar")
              }
            }
            else -> {
              TextButton(
                onClick = onPauseLookahead,
                modifier = Modifier.testTag("player_pause_lookahead_button"),
              ) {
                Icon(
                  imageVector = Icons.Outlined.Pause,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Pausar")
              }
            }
          }
        }
      }

      ProgressiveSegmentedBar(
        playedFraction = 0f,
        preparedFraction = preparedFraction,
        isGeneratingAhead = progressiveState?.isSynthesizing == true,
        isPaused = phase == ProgressiveChapterPhase.PAUSED,
        isError = phase == ProgressiveChapterPhase.RECOVERABLE_ERROR,
        totalChunks = progressiveState?.totalChunks ?: 1,
      )

      Text(
        text =
          "Síntesis de texto: $synthPercent % · Listos ${progressiveState?.chunksCompleted ?: 0}/${progressiveState?.totalChunks ?: 0} fragmentos",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      PlaybackAvailabilityBar(
        playedDurationMs = playbackState.positionMs,
        bufferedDurationMs = playbackState.bufferedDurationMs,
        aheadDurationMs = playbackState.aheadBufferedDurationMs,
        isWaitingForBuffer = playbackState.isWaitingForBuffer,
      )

      ProgressiveStateLegend(
        phase = phase,
        canStartPlayback = true,
        isPlaying = playbackState.isPlaying,
        isSynthesizing = progressiveState?.isSynthesizing == true,
      )
    }
  }
}
