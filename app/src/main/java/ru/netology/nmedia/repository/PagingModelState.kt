package ru.netology.nmedia.repository

data class PagingModelState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: Boolean = false,
)
