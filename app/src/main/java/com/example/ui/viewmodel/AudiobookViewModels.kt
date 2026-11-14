package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.data.repository.AudiobookRepository
import com.example.domain.audio.AudioSynthesisProvider
import com.example.domain.audio.BookGenerationProgress
import com.example.domain.model.Book
import com.example.domain.model.BookWithChapters
import com.example.domain.model.Chapter
import com.example.domain.model.NarrationEstimator
import com.example.domain.model.NarratorVoice
import com.example.player.AudiobookPlaybackController
import com.example.player.PlaybackUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ImportUiState {
  data object Idle : ImportUiState
  data object Importing : ImportUiState
  data class Success(val book: Book) : ImportUiState
  data class Error(val message: String) : ImportUiState
}

class LibraryViewModel(
  private val repository: AudiobookRepository,
  private val playbackController: AudiobookPlaybackController,
) : ViewModel() {

  val books: StateFlow<List<Book>> =
    repository
      .observeBooks()
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
      )

  val audioProvider: AudioSynthesisProvider = repository.audioSynthesisProvider
  val playbackState: StateFlow<PlaybackUiState> = playbackController.uiState

  private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
  val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

  init {
    playbackController.restoreLastPlaybackIfIdle()
  }

  fun importEpub(uri: Uri) {
    if (_importState.value is ImportUiState.Importing) return
    viewModelScope.launch {
      _importState.value = ImportUiState.Importing
      val result = repository.importEpub(uri)
      _importState.value =
        result.fold(
          onSuccess = { book -> ImportUiState.Success(book) },
          onFailure = { error ->
            ImportUiState.Error(
              error.localizedMessage ?: "No se pudo analizar el archivo EPUB seleccionado."
            )
          },
        )
    }
  }

  fun deleteBook(bookId: String) {
    viewModelScope.launch {
      playbackController.stopIfBookCleared(bookId)
      repository.deleteBook(bookId)
    }
  }

  fun togglePlayPause() = playbackController.togglePlayPause()

  fun resetImportState() {
    _importState.value = ImportUiState.Idle
  }
}

sealed interface BookDetailUiState {
  data object Loading : BookDetailUiState
  data class Loaded(
    val data: BookWithChapters,
    val audioStorageBytes: Long,
    val generationProgress: BookGenerationProgress?,
  ) : BookDetailUiState

  data object NotFound : BookDetailUiState
}

class BookDetailViewModel(
  private val bookId: String,
  private val repository: AudiobookRepository,
  private val playbackController: AudiobookPlaybackController,
) : ViewModel() {

  val audioProvider: AudioSynthesisProvider = repository.audioSynthesisProvider
  val playbackState: StateFlow<PlaybackUiState> = playbackController.uiState

  private val _storageBytes = MutableStateFlow(0L)
  private val _exportFeedback = MutableStateFlow<String?>(null)
  val exportFeedback: StateFlow<String?> = _exportFeedback.asStateFlow()

  init {
    refreshStorageBytes()
    playbackController.ensureConnected()
  }

  val uiState: StateFlow<BookDetailUiState> =
    combine(
        repository.observeBookWithChapters(bookId),
        _storageBytes,
        repository.generationProgress.map { it[bookId] },
      ) { bookWithChapters, storageBytes, progress ->
        if (bookWithChapters == null) {
          BookDetailUiState.NotFound
        } else {
          val liveBytes =
            if (progress?.isRunning == false) {
              repository.getBookAudioStorageBytes(bookId)
            } else {
              storageBytes
            }
          BookDetailUiState.Loaded(
            data = bookWithChapters,
            audioStorageBytes = liveBytes,
            generationProgress = progress,
          )
        }
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BookDetailUiState.Loading,
      )

  fun refreshStorageBytes() {
    viewModelScope.launch { _storageBytes.value = repository.getBookAudioStorageBytes(bookId) }
  }

  fun selectVoice(voice: NarratorVoice) {
    viewModelScope.launch { repository.updateSelectedVoice(bookId, voice) }
  }

  fun generateAudiobook() {
    Log.i("BookDetailVM", "generateAudiobook() invocado para bookId=$bookId")
    repository.clearGenerationError(bookId)
    repository.startAudiobookGeneration(bookId)
  }

  fun cancelGeneration() {
    repository.cancelAudiobookGeneration(bookId)
    refreshStorageBytes()
  }

  fun clearGenerationError() {
    repository.clearGenerationError(bookId)
  }

  fun playChapter(chapterIndex: Int? = null) {
    val loaded = uiState.value as? BookDetailUiState.Loaded ?: return
    val targetPos =
      if (chapterIndex == null || chapterIndex == loaded.data.book.lastPlayedChapterIndex) {
        loaded.data.book.lastPlayedPositionMs
      } else {
        0L
      }
    playbackController.playBook(
      book = loaded.data.book,
      chapters = loaded.data.chapters,
      startChapterIndex = chapterIndex,
      startPositionMs = targetPos,
    )
  }

  fun togglePlayPause() = playbackController.togglePlayPause()

  fun exportChapterWav(chapter: Chapter, destinationUri: Uri) {
    viewModelScope.launch {
      val res = repository.exportChapterWav(chapter, destinationUri)
      _exportFeedback.value =
        res.fold(
          onSuccess = { bytes ->
            "Capítulo exportado correctamente (${NarrationEstimator.formatFileSize(bytes)})."
          },
          onFailure = { err -> err.localizedMessage ?: "No se pudo exportar el capítulo." },
        )
    }
  }

  fun exportFullAudiobookWav(destinationUri: Uri) {
    val loaded = uiState.value as? BookDetailUiState.Loaded ?: return
    viewModelScope.launch {
      val res = repository.exportFullAudiobookWav(loaded.data.chapters, destinationUri)
      _exportFeedback.value =
        res.fold(
          onSuccess = { bytes ->
            "Audiolibro completo exportado (${NarrationEstimator.formatFileSize(bytes)})."
          },
          onFailure = { err ->
            err.localizedMessage ?: "No se pudo exportar el audiolibro completo."
          },
        )
    }
  }

  fun clearExportFeedback() {
    _exportFeedback.value = null
  }

  fun deleteGeneratedAudioOnly() {
    viewModelScope.launch {
      playbackController.stopIfBookCleared(bookId)
      repository.deleteGeneratedAudioOnly(bookId)
      _storageBytes.value = 0L
      _exportFeedback.value = "Audios generados eliminados. El texto del EPUB se conserva."
    }
  }

  fun deleteBook(onDeleted: () -> Unit) {
    viewModelScope.launch {
      playbackController.stopIfBookCleared(bookId)
      repository.deleteBook(bookId)
      onDeleted()
    }
  }

  fun suggestedChapterFileName(bookTitle: String, chapter: Chapter): String {
    val safeBook = repository.storageManager.sanitizeFileName(bookTitle)
    val safeChapter = repository.storageManager.sanitizeFileName(chapter.title)
    val num = chapter.chapterIndex.toString().padStart(2, '0')
    return "${safeBook}_cap_${num}_${safeChapter}.wav"
  }

  fun suggestedBookFileName(bookTitle: String): String {
    val safeBook = repository.storageManager.sanitizeFileName(bookTitle)
    return "${safeBook}_audiolibro.wav"
  }
}

sealed interface ChapterReaderUiState {
  data object Loading : ChapterReaderUiState
  data class Loaded(
    val bookTitle: String,
    val chapters: List<Chapter>,
    val currentChapter: Chapter,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
  ) : ChapterReaderUiState

  data object NotFound : ChapterReaderUiState
}

class ChapterReaderViewModel(
  bookId: String,
  initialChapterIndex: Int,
  repository: AudiobookRepository,
) : ViewModel() {

  private val selectedChapterIndex = MutableStateFlow(initialChapterIndex)

  val uiState: StateFlow<ChapterReaderUiState> =
    combine(repository.observeBookWithChapters(bookId), selectedChapterIndex) {
        bookWithChapters,
        targetIndex ->
        if (bookWithChapters == null || bookWithChapters.chapters.isEmpty()) {
          ChapterReaderUiState.NotFound
        } else {
          val chapters = bookWithChapters.chapters
          val currentPosition =
            chapters.indexOfFirst { it.chapterIndex == targetIndex }.let { idx ->
              if (idx >= 0) idx else 0
            }
          val current = chapters[currentPosition]
          ChapterReaderUiState.Loaded(
            bookTitle = bookWithChapters.book.title,
            chapters = chapters,
            currentChapter = current,
            hasPrevious = currentPosition > 0,
            hasNext = currentPosition < chapters.lastIndex,
          )
        }
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChapterReaderUiState.Loading,
      )

  fun selectPreviousChapter() {
    val state = uiState.value as? ChapterReaderUiState.Loaded ?: return
    val currentPos = state.chapters.indexOfFirst { it.id == state.currentChapter.id }
    if (currentPos > 0) {
      selectedChapterIndex.value = state.chapters[currentPos - 1].chapterIndex
    }
  }

  fun selectNextChapter() {
    val state = uiState.value as? ChapterReaderUiState.Loaded ?: return
    val currentPos = state.chapters.indexOfFirst { it.id == state.currentChapter.id }
    if (currentPos in 0 until state.chapters.lastIndex) {
      selectedChapterIndex.value = state.chapters[currentPos + 1].chapterIndex
    }
  }
}

class PlayerViewModel(
  private val bookId: String,
  private val startChapterIndex: Int?,
  repository: AudiobookRepository,
  private val playbackController: AudiobookPlaybackController,
  private val savedStateHandle: SavedStateHandle? = null,
) : ViewModel() {

  val bookWithChapters: StateFlow<BookWithChapters?> =
    repository
      .observeBookWithChapters(bookId)
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
      )

  val playbackState: StateFlow<PlaybackUiState> = playbackController.uiState

  private var autoStartedFallback = false
  private var autoStarted: Boolean
    get() = savedStateHandle?.get<Boolean>(KEY_AUTO_STARTED) ?: autoStartedFallback
    set(value) {
      autoStartedFallback = value
      savedStateHandle?.set(KEY_AUTO_STARTED, value)
    }

  init {
    playbackController.ensureConnected()
    viewModelScope.launch {
      combine(bookWithChapters, playbackController.uiState) { data, state -> data to state }
        .collect { (data, state) ->
          if (autoStarted || data == null || !state.isConnected) return@collect

          val alreadyLoadedInPlayer =
            state.activeBookId == bookId && playbackController.hasActiveMediaInPlayer()
          val matchesRequestedChapter =
            startChapterIndex == null || state.currentChapterIndex == startChapterIndex

          if (alreadyLoadedInPlayer && matchesRequestedChapter) {
            // Reconnected to active playback in AudiobookPlaybackService: resume cleanly without resetting position.
            autoStarted = true
          } else if (alreadyLoadedInPlayer && savedStateHandle?.contains(KEY_AUTO_STARTED) == true) {
            autoStarted = true
          } else {
            autoStarted = true
            playbackController.playBook(
              book = data.book,
              chapters = data.chapters,
              startChapterIndex = startChapterIndex,
            )
          }
        }
    }
  }

  fun togglePlayPause() = playbackController.togglePlayPause()

  fun seekTo(positionMs: Long) = playbackController.seekTo(positionMs)

  fun seekForward() = playbackController.seekForward()

  fun seekBack() = playbackController.seekBack()

  fun nextChapter() = playbackController.nextChapter()

  fun previousChapter() = playbackController.previousChapter()

  fun jumpToChapter(chapterIndex: Int) {
    val state = playbackController.uiState.value
    if (state.activeBookId == bookId && playbackController.hasActiveMediaInPlayer()) {
      playbackController.jumpToChapter(chapterIndex)
    } else {
      val data = bookWithChapters.value ?: return
      playbackController.playBook(data.book, data.chapters, startChapterIndex = chapterIndex, startPositionMs = 0L)
    }
  }

  fun clearError() = playbackController.clearError()

  companion object {
    private const val KEY_AUTO_STARTED = "player_initial_auto_started"
  }
}

class AudiobookViewModelFactory(
  private val context: Context,
  private val bookId: String? = null,
  private val initialChapterIndex: Int = 1,
  private val startPlaybackChapterIndex: Int? = null,
) : ViewModelProvider.Factory {

  private val repository: AudiobookRepository by lazy { AudiobookRepository.getInstance(context) }
  private val playbackController: AudiobookPlaybackController by lazy {
    AudiobookPlaybackController.getInstance(context)
  }

  override fun <T : ViewModel> create(modelClass: Class<T>): T =
    create(modelClass, CreationExtras.Empty)

  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
    return when {
      modelClass.isAssignableFrom(LibraryViewModel::class.java) ->
        LibraryViewModel(repository, playbackController) as T
      modelClass.isAssignableFrom(BookDetailViewModel::class.java) ->
        BookDetailViewModel(requireNotNull(bookId), repository, playbackController) as T
      modelClass.isAssignableFrom(ChapterReaderViewModel::class.java) ->
        ChapterReaderViewModel(requireNotNull(bookId), initialChapterIndex, repository) as T
      modelClass.isAssignableFrom(PlayerViewModel::class.java) -> {
        val savedStateHandle = runCatching { extras.createSavedStateHandle() }.getOrNull()
        PlayerViewModel(
          requireNotNull(bookId),
          startPlaybackChapterIndex,
          repository,
          playbackController,
          savedStateHandle,
        ) as T
      }
      else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
  }
}
