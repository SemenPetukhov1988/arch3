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
                LoadType.REFRESH -> {
                    service.getLatest(state.config.pageSize)
                }

                LoadType.PREPEND -> {
                    // СКРОЛЛ ВВЕРХ: нужны более старые посты → берём ключ BEFORE
                    val remoteKeyBefore = postRemoteKeyDao.min(PostRemoteKeyEntity.KeyType.BEFORE)
                    val key = remoteKeyBefore ?: state.pages.firstOrNull()?.firstOrNull()?.id
                    service.getBefore(key ?: 0, state.config.pageSize)
                }

                LoadType.APPEND -> {
                    // СКРОЛЛ ВНИЗ: нужны более новые посты → берём ключ AFTER
                    val remoteKeyAfter = postRemoteKeyDao.max(PostRemoteKeyEntity.KeyType.AFTER)
                    val key = remoteKeyAfter ?: state.pages.lastOrNull()?.lastOrNull()?.id
                    service.getAfter(key ?: 0, state.config.pageSize)
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
                                // При REFRESH обновляем оба ключа
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

                        LoadType.PREPEND -> {
                            // ПОДГРУЗКА ВВЕРХ: обновляем ключ BEFORE (это будет самый старый из новых постов)
                            if (body.isNotEmpty()) {
                                postRemoteKeyDao.insertOrUpdate(
                                    PostRemoteKeyEntity(
                                        type = PostRemoteKeyEntity.KeyType.BEFORE,
                                        id = body.last().id, // самый старый в новой пачке
                                    )
                                )
                            }
                        }

                        LoadType.APPEND -> {
                            // ПОДГРУЗКА ВНИЗ: обновляем ключ AFTER (это будет самый новый из новых постов)
                            if (body.isNotEmpty()) {
                                postRemoteKeyDao.insertOrUpdate(
                                    PostRemoteKeyEntity(
                                        type = PostRemoteKeyEntity.KeyType.AFTER,
                                        id = body.first().id, // самый новый в новой пачке
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