package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.Book
import com.example.domain.model.NarrationEstimator
import com.example.ui.components.ArchitectureInfoSheet
import com.example.ui.components.BookCover
import com.example.ui.components.InfoChip
import com.example.ui.components.MiniPlayerBar
import com.example.ui.components.formatIntegerEs
import com.example.ui.viewmodel.ImportUiState
import com.example.ui.viewmodel.LibraryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
  viewModel: LibraryViewModel,
  onOpenBook: (String) -> Unit,
  onOpenPlayer: (String) -> Unit,
) {
  val books by viewModel.books.collectAsStateWithLifecycle()
  val importState by viewModel.importState.collectAsStateWithLifecycle()
  val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()

  var showArchitectureSheet by remember { mutableStateOf(false) }
  var bookToDelete by remember { mutableStateOf<Book?>(null) }

  val epubPickerLauncher =
    rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        viewModel.importEpub(uri)
      }
    }

  val launchEpubPicker = {
    epubPickerLauncher.launch(
      arrayOf(
        "application/epub+zip",
        "application/zip",
        "application/octet-stream",
        "*/*",
      )
    )
  }

  LaunchedEffect(importState) {
    val current = importState
    if (current is ImportUiState.Success) {
      viewModel.resetImportState()
      onOpenBook(current.book.id)
    }
  }

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
        title = {
          Column {
            Text(
              text = stringResource(R.string.library_title),
              style = MaterialTheme.typography.headlineMedium,
            )
            Text(
              text = stringResource(R.string.library_subtitle),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
        actions = {
          IconButton(
            onClick = { showArchitectureSheet = true },
            modifier =
              Modifier.minimumInteractiveComponentSize().testTag("architecture_info_button"),
          ) {
            Icon(
              imageVector = Icons.Outlined.Info,
              contentDescription = stringResource(R.string.action_info),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
      )
    },
    floatingActionButton = {
      if (books.isNotEmpty() && importState !is ImportUiState.Importing) {
        ExtendedFloatingActionButton(
          onClick = launchEpubPicker,
          modifier = Modifier.testTag("import_epub_fab"),
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
          icon = { Icon(imageVector = Icons.Rounded.Add, contentDescription = null) },
          text = { Text(text = stringResource(R.string.action_import_epub)) },
        )
      }
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(modifier = Modifier.fillMaxSize().widthIn(max = 760.dp)) {
        if (importState is ImportUiState.Importing) {
          ImportingBanner(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }

        if (books.isEmpty()) {
          EmptyLibraryState(
            isImporting = importState is ImportUiState.Importing,
            onSelectEpub = launchEpubPicker,
            modifier = Modifier.weight(1f).fillMaxWidth(),
          )
        } else {
          LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
          ) {
            item {
              Text(
                text =
                  if (books.size == 1) "1 libro analizado"
                  else "${books.size} libros analizados",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
              )
            }
            items(books, key = { it.id }) { book ->
              BookLibraryCard(
                book = book,
                onClick = { onOpenBook(book.id) },
                onRequestDelete = { bookToDelete = book },
              )
            }
          }
        }

        if (playbackState.hasActiveAudio) {
          MiniPlayerBar(
            playbackState = playbackState,
            onTogglePlayPause = viewModel::togglePlayPause,
            onOpenPlayer = { playbackState.activeBookId?.let(onOpenPlayer) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
          )
        }
      }
    }
  }

  if (importState is ImportUiState.Error) {
    val errorMessage = (importState as ImportUiState.Error).message
    AlertDialog(
      onDismissRequest = { viewModel.resetImportState() },
      title = { Text("No se pudo leer el EPUB") },
      text = { Text(errorMessage) },
      confirmButton = {
        TextButton(onClick = { viewModel.resetImportState() }) { Text("Entendido") }
      },
    )
  }

  bookToDelete?.let { book ->
    AlertDialog(
      onDismissRequest = { bookToDelete = null },
      title = { Text(stringResource(R.string.delete_book_confirm_title)) },
      text = { Text(stringResource(R.string.delete_book_confirm_body)) },
      dismissButton = {
        TextButton(onClick = { bookToDelete = null }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            viewModel.deleteBook(book.id)
            bookToDelete = null
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

  if (showArchitectureSheet) {
    ArchitectureInfoSheet(
      audioProvider = viewModel.audioProvider,
      onDismiss = { showArchitectureSheet = false },
    )
  }
}

@Composable
private fun ImportingBanner(modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.primaryContainer,
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = stringResource(R.string.analyzing_epub),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
      )
      LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyLibraryState(
  isImporting: Boolean,
  onSelectEpub: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Surface(
      modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(),
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      tonalElevation = 1.dp,
    ) {
      Image(
        painter = painterResource(id = R.drawable.img_empty_library),
        contentDescription = "Ilustración de libro abierto listo para audiolibro",
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().height(210.dp),
      )
    }

    Spacer(modifier = Modifier.height(24.dp))

    Text(
      text = stringResource(R.string.empty_library_title),
      style = MaterialTheme.typography.headlineSmall,
      color = MaterialTheme.colorScheme.onBackground,
      textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
      text = stringResource(R.string.empty_library_body),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.widthIn(max = 420.dp),
    )

    Spacer(modifier = Modifier.height(24.dp))

    Button(
      onClick = onSelectEpub,
      enabled = !isImporting,
      modifier = Modifier.height(52.dp).testTag("select_epub_button"),
      shape = MaterialTheme.shapes.medium,
    ) {
      Icon(imageVector = Icons.Rounded.FileOpen, contentDescription = null)
      Spacer(modifier = Modifier.width(10.dp))
      Text(
        text = stringResource(R.string.action_select_epub),
        style = MaterialTheme.typography.labelLarge,
      )
    }

    Spacer(modifier = Modifier.height(24.dp))

    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      StepPill(number = "1", text = "Elegir EPUB")
      StepPill(number = "2", text = "Extraer capítulos")
      StepPill(number = "3", text = "Generar y escuchar")
    }
  }
}

@Composable
private fun StepPill(number: String, text: String) {
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = number,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookLibraryCard(
  book: Book,
  onClick: () -> Unit,
  onRequestDelete: () -> Unit,
) {
  Card(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().testTag("book_card_${book.id}"),
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      horizontalArrangement = Arrangement.spacedBy(14.dp),
      verticalAlignment = Alignment.Top,
    ) {
      BookCover(
        title = book.title,
        author = book.author,
        coverPath = book.coverImagePath,
        modifier = Modifier.width(74.dp).height(108.dp),
      )

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.Top,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = book.title,
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = book.author,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          IconButton(
            onClick = onRequestDelete,
            modifier =
              Modifier.size(48.dp)
                .minimumInteractiveComponentSize()
                .testTag("delete_book_${book.id}"),
          ) {
            Icon(
              imageVector = Icons.Outlined.DeleteOutline,
              contentDescription = stringResource(R.string.action_delete_book),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          InfoChip(
            icon = Icons.Outlined.AutoStories,
            text = "${book.totalChapters} cap.",
          )
          InfoChip(
            icon = Icons.Outlined.TextFields,
            text = "${formatIntegerEs(book.totalWords)} pal.",
          )
          InfoChip(
            icon = Icons.Outlined.Schedule,
            text = NarrationEstimator.formatDuration(book.estimatedMinutes),
          )
          InfoChip(
            icon = Icons.Outlined.RecordVoiceOver,
            text = "Voz ${book.selectedVoice.displayName.lowercase()}",
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
          )
        }
      }
    }
  }
}
