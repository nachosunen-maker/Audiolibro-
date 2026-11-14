package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderDelete
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.audio.AudioSynthesisProvider
import com.example.domain.audio.BookGenerationProgress
import com.example.domain.model.AudioGenerationStatus
import com.example.domain.model.Book
import com.example.domain.model.BookWithChapters
import com.example.domain.model.Chapter
import com.example.domain.model.NarrationEstimator
import com.example.domain.model.NarratorVoice
import com.example.ui.components.BookCover
import com.example.ui.components.InfoChip
import com.example.ui.components.LegalNoticesDialog
import com.example.ui.components.MiniPlayerBar
import com.example.ui.components.formatIntegerEs
import com.example.ui.viewmodel.BookDetailUiState
import com.example.ui.viewmodel.BookDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
  viewModel: BookDetailViewModel,
  onNavigateBack: () -> Unit,
  onOpenChapterText: (bookId: String, chapterIndex: Int) -> Unit,
  onOpenPlayer: (bookId: String, startChapterIndex: Int?) -> Unit,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
  val exportFeedback by viewModel.exportFeedback.collectAsStateWithLifecycle()

  val snackbarHostState = remember { SnackbarHostState() }
  var showDeleteBookDialog by remember { mutableStateOf(false) }
  var showDeleteAudioDialog by remember { mutableStateOf(false) }
  var showLegalNoticesDialog by remember { mutableStateOf(false) }
  var chapterQuery by remember { mutableStateOf("") }
  var pendingChapterToExport by remember { mutableStateOf<Chapter?>(null) }

  val createChapterWavLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
      val target = pendingChapterToExport
      pendingChapterToExport = null
      if (uri != null && target != null) {
        viewModel.exportChapterWav(target, uri)
      }
    }

  val createFullBookWavLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
      if (uri != null) {
        viewModel.exportFullAudiobookWav(uri)
      }
    }

  LaunchedEffect(exportFeedback) {
    val msg = exportFeedback
    if (!msg.isNullOrBlank()) {
      snackbarHostState.showSnackbar(msg)
      viewModel.clearExportFeedback()
    }
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    contentWindowInsets = WindowInsets.safeDrawing,
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
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
            modifier = Modifier.minimumInteractiveComponentSize().testTag("back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
              contentDescription = stringResource(R.string.action_back),
            )
          }
        },
        title = {
          Text(
            text = "Ficha del audiolibro",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        },
        actions = {
          if (uiState is BookDetailUiState.Loaded) {
            IconButton(
              onClick = { showDeleteBookDialog = true },
              modifier = Modifier.minimumInteractiveComponentSize().testTag("detail_delete_button"),
            ) {
              Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = stringResource(R.string.action_delete_book),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
      when (val state = uiState) {
        BookDetailUiState.Loading -> {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
        }
        BookDetailUiState.NotFound -> {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
              text = "No se encontró el libro solicitado.",
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        is BookDetailUiState.Loaded -> {
          val bookWithChapters = state.data
          val book = bookWithChapters.book
          val chapters = bookWithChapters.chapters
          val filteredChapters =
            remember(chapters, chapterQuery) {
              val q = chapterQuery.trim()
              if (q.isEmpty()) chapters
              else
                chapters.filter { ch ->
                  ch.title.contains(q, ignoreCase = true) ||
                    ch.chapterIndex.toString().contains(q)
                }
            }

          Column(modifier = Modifier.fillMaxSize().widthIn(max = 760.dp)) {
            LazyColumn(
              modifier = Modifier.weight(1f).fillMaxWidth(),
              contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
              verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
              item { BookSummaryHero(book = book) }

              item {
                VoiceSelectionSection(
                  selectedVoice = book.selectedVoice,
                  enabled = state.generationProgress?.isRunning != true,
                  onSelectVoice = viewModel::selectVoice,
                )
              }

              item {
                AudiobookGenerationSection(
                  bookWithChapters = bookWithChapters,
                  audioProvider = viewModel.audioProvider,
                  progress = state.generationProgress,
                  onGenerate = viewModel::generateAudiobook,
                  onCancel = viewModel::cancelGeneration,
                  onDismissError = viewModel::clearGenerationError,
                  onOpenPlayer = { onOpenPlayer(book.id, null) },
                  onOpenLegalNotices = { showLegalNoticesDialog = true },
                )
              }

              item {
                StorageAndExportCard(
                  bookWithChapters = bookWithChapters,
                  storageBytes = state.audioStorageBytes,
                  onExportFullBook = {
                    createFullBookWavLauncher.launch(viewModel.suggestedBookFileName(book.title))
                  },
                  onDeleteAudioOnly = { showDeleteAudioDialog = true },
                )
              }

              item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                  Text(
                    text = "${stringResource(R.string.chapters_section_title)} (${chapters.size})",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                  )
                  if (chapters.size > 5) {
                    OutlinedTextField(
                      value = chapterQuery,
                      onValueChange = { chapterQuery = it },
                      modifier = Modifier.fillMaxWidth().testTag("chapter_search_input"),
                      placeholder = { Text(stringResource(R.string.search_chapters_hint)) },
                      leadingIcon = {
                        Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
                      },
                      trailingIcon = {
                        if (chapterQuery.isNotEmpty()) {
                          IconButton(onClick = { chapterQuery = "" }) {
                            Icon(
                              imageVector = Icons.Rounded.Close,
                              contentDescription = "Limpiar búsqueda",
                            )
                          }
                        }
                      },
                      singleLine = true,
                      shape = MaterialTheme.shapes.medium,
                    )
                  }
                }
              }

              if (filteredChapters.isEmpty()) {
                item {
                  Text(
                    text = "No hay capítulos que coincidan con \"$chapterQuery\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                  )
                }
              } else {
                items(filteredChapters, key = { it.id }) { chapter ->
                  ChapterItemCard(
                    chapter = chapter,
                    onOpenText = { onOpenChapterText(book.id, chapter.chapterIndex) },
                    onListenChapter = {
                      viewModel.playChapter(chapter.chapterIndex)
                      onOpenPlayer(book.id, chapter.chapterIndex)
                    },
                    onExportChapter = {
                      pendingChapterToExport = chapter
                      createChapterWavLauncher.launch(
                        viewModel.suggestedChapterFileName(book.title, chapter)
                      )
                    },
                  )
                }
              }
            }

            if (playbackState.hasActiveAudio) {
              MiniPlayerBar(
                playbackState = playbackState,
                onTogglePlayPause = viewModel::togglePlayPause,
                onOpenPlayer = {
                  playbackState.activeBookId?.let { activeId -> onOpenPlayer(activeId, null) }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
              )
            }
          }
        }
      }
    }
  }

  if (showDeleteBookDialog) {
    AlertDialog(
      onDismissRequest = { showDeleteBookDialog = false },
      title = { Text(stringResource(R.string.delete_book_confirm_title)) },
      text = { Text(stringResource(R.string.delete_book_confirm_body)) },
      dismissButton = {
        TextButton(onClick = { showDeleteBookDialog = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            showDeleteBookDialog = false
            viewModel.deleteBook(onDeleted = onNavigateBack)
          }
        ) {
          Text(
            text = stringResource(R.string.action_delete),
            color = MaterialTheme.colorScheme.error,
          )
        }
      },
    )
  }

  if (showDeleteAudioDialog) {
    AlertDialog(
      onDismissRequest = { showDeleteAudioDialog = false },
      title = { Text("¿Eliminar audios generados?") },
      text = {
        Text(
          "Se borrarán todos los archivos WAV generados para este libro y se liberará su espacio. Los capítulos extraídos del EPUB se conservarán intactos."
        )
      },
      dismissButton = {
        TextButton(onClick = { showDeleteAudioDialog = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            showDeleteAudioDialog = false
            viewModel.deleteGeneratedAudioOnly()
          }
        ) {
          Text(
            text = "Eliminar audios",
            color = MaterialTheme.colorScheme.error,
          )
        }
      },
    )
  }

  if (showLegalNoticesDialog) {
    LegalNoticesDialog(onDismiss = { showLegalNoticesDialog = false })
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookSummaryHero(book: Book) {
  var showFullDescription by remember { mutableStateOf(false) }

  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    tonalElevation = 1.dp,
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
      ) {
        BookCover(
          title = book.title,
          author = book.author,
          coverPath = book.coverImagePath,
          modifier = Modifier.width(98.dp).height(142.dp),
        )

        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = book.title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = book.author,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
          )

          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            InfoChip(icon = Icons.Outlined.Language, text = book.language)
            if (book.publisher.isNotBlank()) {
              InfoChip(icon = Icons.Outlined.AutoAwesome, text = book.publisher)
            }
          }

          Text(
            text = "Archivo: ${book.sourceFileName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        MetricBox(
          label = "Capítulos",
          value = book.totalChapters.toString(),
          modifier = Modifier.weight(1f),
        )
        MetricBox(
          label = "Palabras",
          value = formatIntegerEs(book.totalWords),
          modifier = Modifier.weight(1f),
        )
        MetricBox(
          label = "Duración est.",
          value = NarrationEstimator.formatDuration(book.estimatedMinutes),
          modifier = Modifier.weight(1f),
        )
      }

      if (book.description.isNotBlank()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = book.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (showFullDescription) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
          )
          if (book.description.length > 140) {
            TextButton(
              onClick = { showFullDescription = !showFullDescription },
              contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) {
              Text(if (showFullDescription) "Mostrar menos" else "Leer sinopsis completa")
            }
          }
        }
      }
    }
  }
}

@Composable
private fun MetricBox(label: String, value: String, modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier,
    shape = MaterialTheme.shapes.small,
    color = MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = value,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun VoiceSelectionSection(
  selectedVoice: NarratorVoice,
  enabled: Boolean,
  onSelectVoice: (NarratorVoice) -> Unit,
) {
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
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = stringResource(R.string.voice_section_title),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text =
            "Selecciona la única voz que narrará todos los capítulos y fragmentos de este libro.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        VoiceOptionCard(
          title = stringResource(R.string.voice_male_title),
          description = NarratorVoice.MALE.description,
          icon = Icons.Outlined.RecordVoiceOver,
          selected = selectedVoice == NarratorVoice.MALE,
          enabled = enabled,
          onClick = { onSelectVoice(NarratorVoice.MALE) },
          modifier = Modifier.weight(1f).testTag("voice_male_card"),
        )
        VoiceOptionCard(
          title = stringResource(R.string.voice_female_title),
          description = NarratorVoice.FEMALE.description,
          icon = Icons.Outlined.GraphicEq,
          selected = selectedVoice == NarratorVoice.FEMALE,
          enabled = enabled,
          onClick = { onSelectVoice(NarratorVoice.FEMALE) },
          modifier = Modifier.weight(1f).testTag("voice_female_card"),
        )
      }
    }
  }
}

@Composable
private fun VoiceOptionCard(
  title: String,
  description: String,
  icon: ImageVector,
  selected: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val containerColor =
    if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
  val borderColor =
    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
  val titleColor =
    if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface

  Card(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.minimumInteractiveComponentSize(),
    shape = MaterialTheme.shapes.medium,
    colors = CardDefaults.cardColors(containerColor = containerColor),
    border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(22.dp),
        )
        if (selected) {
          Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = "Voz seleccionada",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
          )
        }
      }
      Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = titleColor,
      )
      Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = titleColor.copy(alpha = 0.85f),
      )
    }
  }
}

@Composable
private fun AudiobookGenerationSection(
  bookWithChapters: BookWithChapters,
  audioProvider: AudioSynthesisProvider,
  progress: BookGenerationProgress?,
  onGenerate: () -> Unit,
  onCancel: () -> Unit,
  onDismissError: () -> Unit,
  onOpenPlayer: () -> Unit,
  onOpenLegalNotices: () -> Unit,
) {
  val book = bookWithChapters.book
  val chapters = bookWithChapters.chapters
  val readyCount = bookWithChapters.readyChaptersCount
  val totalCount = chapters.size
  val isRunning = progress?.isRunning == true
  val hasPartialProgress = readyCount > 0 && readyCount < totalCount

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
        Text(
          text = "Conversión a audiolibro",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Surface(
          shape = RoundedCornerShape(12.dp),
          color =
            if (bookWithChapters.isCompleteAudiobookReady) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ) {
          Text(
            text = "$readyCount / $totalCount cap.",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color =
              if (bookWithChapters.isCompleteAudiobookReady) MaterialTheme.colorScheme.onSecondaryContainer
              else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "100 % local sin conexión · ${audioProvider.modelId}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
        TextButton(
          onClick = onOpenLegalNotices,
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
          modifier = Modifier.testTag("detail_legal_notices_button"),
        ) {
          Text("Licencias y voz", style = MaterialTheme.typography.labelMedium)
        }
      }

      if (!audioProvider.isConfigured) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = MaterialTheme.shapes.medium,
          color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
          ) {
            Icon(
              imageVector = Icons.Outlined.WarningAmber,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.size(20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(
                text = "Modelo local no disponible (${audioProvider.modelId})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
              )
              Text(
                text =
                  "No se encontraron los archivos empaquetados de Piper / sherpa-onnx en assets. Reinstala o compila el paquete completo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
              )
            }
          }
        }
      }

      if (isRunning && progress != null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          val fraction =
            if (totalCount > 0) {
              (progress.completedChapters.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f)
            } else {
              0f
            }
          LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
          )
          Text(
            text = progress.statusMessage.ifBlank { "Generando audio…" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }

      val errorMessage = progress?.errorMessage
      if (!errorMessage.isNullOrBlank()) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = MaterialTheme.shapes.medium,
          color = MaterialTheme.colorScheme.errorContainer,
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = errorMessage,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.weight(1f),
            )
            IconButton(
              onClick = onDismissError,
              modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
              Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Cerrar aviso",
                tint = MaterialTheme.colorScheme.onErrorContainer,
              )
            }
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (isRunning) {
          OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f).height(48.dp).testTag("cancel_generation_button"),
            shape = MaterialTheme.shapes.medium,
          ) {
            Icon(imageVector = Icons.Rounded.Stop, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Pausar generación")
          }
        } else if (!bookWithChapters.isCompleteAudiobookReady) {
          Button(
            onClick = onGenerate,
            modifier = Modifier.weight(1f).height(48.dp).testTag("generate_audiobook_button"),
            shape = MaterialTheme.shapes.medium,
          ) {
            Icon(imageVector = Icons.Outlined.AutoAwesome, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = if (hasPartialProgress) "Reanudar generación" else "Generar audiolibro",
              style = MaterialTheme.typography.labelLarge,
            )
          }
        }

        if (readyCount > 0) {
          val resumeLabel =
            if (book.lastPlayedChapterIndex != null) {
              "Continuar Cap. ${book.lastPlayedChapterIndex}"
            } else {
              "Abrir reproductor"
            }
          Button(
            onClick = onOpenPlayer,
            modifier = Modifier.weight(1f).height(48.dp).testTag("open_player_button"),
            shape = MaterialTheme.shapes.medium,
          ) {
            Icon(imageVector = Icons.Rounded.Headphones, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = resumeLabel,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun StorageAndExportCard(
  bookWithChapters: BookWithChapters,
  storageBytes: Long,
  onExportFullBook: () -> Unit,
  onDeleteAudioOnly: () -> Unit,
) {
  val hasAnyAudio = storageBytes > 0L || bookWithChapters.readyChaptersCount > 0

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
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Outlined.Storage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = "Almacenamiento y exportación",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
        Text(
          text = NarrationEstimator.formatFileSize(storageBytes),
          style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.testTag("audio_storage_size_text"),
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedButton(
          onClick = onExportFullBook,
          enabled = bookWithChapters.isCompleteAudiobookReady,
          modifier = Modifier.weight(1f).height(48.dp).testTag("export_full_audiobook_button"),
          shape = MaterialTheme.shapes.medium,
        ) {
          Icon(imageVector = Icons.Outlined.Download, contentDescription = null)
          Spacer(modifier = Modifier.width(6.dp))
          Text("Exportar WAV completo", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        OutlinedButton(
          onClick = onDeleteAudioOnly,
          enabled = hasAnyAudio,
          modifier = Modifier.height(48.dp).testTag("delete_audio_only_button"),
          shape = MaterialTheme.shapes.medium,
        ) {
          Icon(
            imageVector = Icons.Outlined.FolderDelete,
            contentDescription = "Eliminar audios generados",
          )
        }
      }
    }
  }
}

@Composable
private fun ChapterItemCard(
  chapter: Chapter,
  onOpenText: () -> Unit,
  onListenChapter: () -> Unit,
  onExportChapter: () -> Unit,
) {
  val isReady =
    chapter.audioStatus == AudioGenerationStatus.READY && !chapter.audioFilePath.isNullOrBlank()

  Card(
    onClick = onOpenText,
    modifier = Modifier.fillMaxWidth().testTag("chapter_card_${chapter.chapterIndex}"),
    shape = MaterialTheme.shapes.medium,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          modifier = Modifier.size(40.dp),
          shape = RoundedCornerShape(10.dp),
          color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
          Box(contentAlignment = Alignment.Center) {
            Text(
              text = chapter.chapterIndex.toString().padStart(2, '0'),
              style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }

        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(
            text = chapter.title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text =
              "${formatIntegerEs(chapter.wordCount)} palabras · ${NarrationEstimator.formatDuration(chapter.estimatedMinutes)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (chapter.previewText.isNotBlank()) {
            Text(
              text = chapter.previewText,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        Icon(
          imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
          contentDescription = "Leer texto de ${chapter.title}",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        ChapterStatusBadge(chapter = chapter)

        if (isReady) {
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
              onClick = onExportChapter,
              modifier = Modifier.height(40.dp).testTag("export_chapter_${chapter.chapterIndex}"),
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
              Icon(
                imageVector = Icons.Outlined.Download,
                contentDescription = "Exportar WAV",
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text("WAV", style = MaterialTheme.typography.labelMedium)
            }
            Button(
              onClick = onListenChapter,
              modifier = Modifier.height(40.dp).testTag("play_chapter_${chapter.chapterIndex}"),
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) {
              Icon(
                imageVector = Icons.Outlined.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text("Escuchar", style = MaterialTheme.typography.labelMedium)
            }
          }
        }
      }

      if (!chapter.generationError.isNullOrBlank()) {
        Text(
          text = chapter.generationError,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

@Composable
private fun ChapterStatusBadge(chapter: Chapter) {
  val (label, containerColor, contentColor) =
    when (chapter.audioStatus) {
      AudioGenerationStatus.READY ->
        Triple(
          "Generado · ${NarrationEstimator.formatPlaybackTime(chapter.audioDurationMs)}",
          MaterialTheme.colorScheme.secondaryContainer,
          MaterialTheme.colorScheme.onSecondaryContainer,
        )
      AudioGenerationStatus.GENERATING ->
        Triple(
          "Generando ${chapter.generatedChunks}/${chapter.totalChunks.coerceAtLeast(1)}",
          MaterialTheme.colorScheme.primaryContainer,
          MaterialTheme.colorScheme.onPrimaryContainer,
        )
      AudioGenerationStatus.ERROR ->
        Triple(
          if (chapter.generatedChunks > 0) {
            "Error (${chapter.generatedChunks}/${chapter.totalChunks} frag.)"
          } else {
            "Error"
          },
          MaterialTheme.colorScheme.errorContainer,
          MaterialTheme.colorScheme.onErrorContainer,
        )
      AudioGenerationStatus.NOT_GENERATED ->
        Triple(
          if (chapter.generatedChunks > 0) {
            "Pausado (${chapter.generatedChunks}/${chapter.totalChunks} frag.)"
          } else {
            "Pendiente"
          },
          MaterialTheme.colorScheme.surfaceVariant,
          MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

  Surface(
    shape = RoundedCornerShape(8.dp),
    color = containerColor,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
      color = contentColor,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
  }
}


