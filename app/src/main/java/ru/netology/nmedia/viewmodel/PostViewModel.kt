package ru.netology.nmedia.viewmodel

import android.net.Uri
import androidx.core.net.toFile
import androidx.lifecycle.*
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ru.netology.nmedia.auth.AppAuth
import ru.netology.nmedia.dto.MediaUpload
import ru.netology.nmedia.dto.Post
import ru.netology.nmedia.model.FeedModelState
import ru.netology.nmedia.model.PhotoModel
import ru.netology.nmedia.repository.PagingModelState
import ru.netology.nmedia.repository.PostRepository
import ru.netology.nmedia.util.SingleLiveEvent
import javax.inject.Inject

private val empty = Post(
    id = 0,
    content = "",
    authorId = 0,
    author = "",
    authorAvatar = "",
    likedByMe = false,
    likes = 0,
    published = 0,
)

private val noPhoto = PhotoModel()

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PostViewModel @Inject constructor(
    private val repository: PostRepository,
    auth: AppAuth,
) : ViewModel() {

    // --- Блок для управления SwipeRefreshLayout и индикаторами загрузки ---
    // 1. Получаем PagingData в виде StateFlow, чтобы иметь доступ к методам PagingData.


    // ------------------------------------------------------------------

    // Основной поток данных для адаптера RecyclerView.
    // Здесь мы добавляем признак ownedByMe к каждому посту.
    val data: Flow<PagingData<Post>> = auth.authStateFlow
        .flatMapLatest { (myId, _) ->
            repository.data.map { pagingData ->
                pagingData.map { post ->
                    post.copy(ownedByMe = post.authorId == myId)
                }
            }
        }
        .cachedIn(viewModelScope)

    // Состояние для других операций (например, сохранения поста)
    private val _dataState = MutableLiveData<FeedModelState>()
    val dataState: LiveData<FeedModelState>
        get() = _dataState

    private val edited = MutableLiveData(empty)
    private val _postCreated = SingleLiveEvent<Unit>()
    val postCreated: LiveData<Unit>
        get() = _postCreated

    private val _photo = MutableLiveData(noPhoto)
    val photo: LiveData<PhotoModel>
        get() = _photo

    init {
        loadPosts()
    }

    fun loadPosts() = viewModelScope.launch {
        try {
            _dataState.value = FeedModelState(loading = true)
            // Запуск первой загрузки данных происходит автоматически через repository.data,
            // который мы уже подключили в pagingDataFlow.
            _dataState.value = FeedModelState()
        } catch (e: Exception) {
            _dataState.value = FeedModelState(error = true)
        }
    }

    // Правильный способ обновить ленту (для SwipeRefreshLayout)


    fun save() {
        edited.value?.let {
            viewModelScope.launch {
                try {
                    repository.save(
                        it, _photo.value?.uri?.let { uri -> MediaUpload(uri.toFile()) }
                    )
                    _postCreated.value = Unit
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Здесь можно добавить обработку ошибки сохранения
                }
            }
        }
        edited.value = empty
        _photo.value = noPhoto
    }

    fun edit(post: Post) {
        edited.value = post
    }

    fun changeContent(content: String) {
        val text = content.trim()
        if (edited.value?.content == text) {
            return
        }
        edited.value = edited.value?.copy(content = text)
    }

    fun changePhoto(uri: Uri?) {
        _photo.value = PhotoModel(uri)
    }

    fun likeById(id: Long) {
        TODO()
    }

    fun removeById(id: Long) {
        TODO()
    }
}