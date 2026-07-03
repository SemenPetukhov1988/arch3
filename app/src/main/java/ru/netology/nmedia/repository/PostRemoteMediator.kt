package ru.netology.nmedia.repository

import android.util.Log
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import ru.netology.nmedia.api.ApiService
import ru.netology.nmedia.dao.PostDao
import ru.netology.nmedia.dao.PostRemoteKeyDao
import ru.netology.nmedia.db.AppDb
import ru.netology.nmedia.entity.PostEntity
import ru.netology.nmedia.entity.PostRemoteKeyEntity
import ru.netology.nmedia.entity.toEntity
import ru.netology.nmedia.error.ApiError

@OptIn(ExperimentalPagingApi::class)
class PostRemoteMediator(
    private val service: ApiService,
    private val db: AppDb,
    private val postDao: PostDao,
    private val postRemoteKeyDao: PostRemoteKeyDao,
) : RemoteMediator<Int, PostEntity>() {

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, PostEntity>
    ): MediatorResult {
        return try {
            val response = when (loadType) {
                // --- ЗАМЕНА МЕСТАМИ: APPEND и PREPEND ---
                // БЫЛО: PREPEND -> getBefore, APPEND -> getAfter
                // СТАЛО: PREPEND -> getAfter, APPEND -> getBefore

                LoadType.REFRESH -> {
                    service.getLatest(state.config.pageSize)
                }

                LoadType.PREPEND -> {
                    // Теперь при скролле ВВЕРХ (PREPEND) мы загружаем БОЛЕЕ НОВЫЕ посты
                    // Для этого используем ключ AFTER
                    val remoteKeyAfter = postRemoteKeyDao.max(PostRemoteKeyEntity.KeyType.AFTER)
                    val key = remoteKeyAfter ?: state.pages.firstOrNull()?.firstOrNull()?.id
                    // Используем getAfter, чтобы получить более новые посты
                    service.getAfter(key ?: 0, state.config.pageSize)
                }

                LoadType.APPEND -> {
                    // Теперь при скролле ВНИЗ (APPEND) мы загружаем БОЛЕЕ СТАРЫЕ посты
                    // Для этого используем ключ BEFORE
                    val remoteKeyBefore = postRemoteKeyDao.min(PostRemoteKeyEntity.KeyType.BEFORE)
                    val key = remoteKeyBefore ?: state.pages.lastOrNull()?.lastOrNull()?.id
                    // Используем getBefore, чтобы получить более старые посты
                    service.getBefore(key ?: 0, state.config.pageSize)
                }
            }

            if (!response.isSuccessful) {
                throw ApiError(response.code(), response.message())
            }
            val body = response.body() ?: throw ApiError(response.code(), response.message())

            val endOfPaginationReached = body.isEmpty()

            db.withTransaction {
                if (loadType == LoadType.REFRESH) {
                    postDao.clear()
                    postRemoteKeyDao.clear()
                }

                if (body.isNotEmpty()) {
                    postDao.insert(body.toEntity())
                }

                if (!endOfPaginationReached) {
                    when (loadType) {
                        LoadType.REFRESH -> {
                            if (body.isNotEmpty()) {
                                // При REFRESH обновляем оба ключа (логика не меняется)
                                postRemoteKeyDao.insertOrUpdate(
                                    PostRemoteKeyEntity(
                                        type = PostRemoteKeyEntity.KeyType.AFTER,
                                        id = body.first().id,
                                    )
                                )
                                postRemoteKeyDao.insertOrUpdate(
                                    PostRemoteKeyEntity(
                                        type = PostRemoteKeyEntity.KeyType.BEFORE,
                                        id = body.last().id,
                                    )
                                )
                            }
                        }

                        // --- ЗАМЕНА МЕСТАМИ: APPEND и PREPEND ---
                        // БЫЛО: PREPEND обновлял BEFORE, APPEND обновлял AFTER
                        // СТАЛО: PREPEND обновляет AFTER, APPEND обновляет BEFORE

                        LoadType.PREPEND -> {
                            // Теперь при подгрузке ВВЕРХ (новее) обновляем ключ AFTER
                            if (body.isNotEmpty()) {
                                postRemoteKeyDao.insertOrUpdate(
                                    PostRemoteKeyEntity(
                                        type = PostRemoteKeyEntity.KeyType.AFTER,
                                        id = body.first().id, // самый новый в новой пачке
                                    )
                                )
                            }
                        }

                        LoadType.APPEND -> {
                            // Теперь при подгрузке ВНИЗ (старее) обновляем ключ BEFORE
                            if (body.isNotEmpty()) {
                                postRemoteKeyDao.insertOrUpdate(
                                    PostRemoteKeyEntity(
                                        type = PostRemoteKeyEntity.KeyType.BEFORE,
                                        id = body.last().id, // самый старый в новой пачке
                                    )
                                )
                            }
                        }
                    }
                }
            }

            MediatorResult.Success(endOfPaginationReached = endOfPaginationReached)

        } catch (e: Exception) {
            if (e is CancellationException) {
                throw e
            }
            MediatorResult.Error(e)
        }
    }
}