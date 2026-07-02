package ru.netology.nmedia.repository

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
            // --- Логика запроса к серверу (остается без изменений) ---
            val response = when (loadType) {
                LoadType.REFRESH -> {
                    // Для REFRESH мы всегда запрашиваем последние посты.
                    service.getLatest(state.config.pageSize)
                }

                LoadType.PREPEND -> {
                    // Для PREPEND убираем проверку на null, чтобы всегда делать запрос.
                    // Если ключа нет, передаем null или 0, в зависимости от логики API.
                    // Если API не поддерживает getBefore(null), можно передать ID первого поста.
                    val remoteKeyBefore = postRemoteKeyDao.min(PostRemoteKeyEntity.KeyType.BEFORE)
                    // Если ключа нет, можно передать null или 0, если API это поддерживает.
                    // Если нет - передать ID первого элемента в текущем списке.
                    val key = remoteKeyBefore ?: state.pages.firstOrNull()?.firstOrNull()?.id

                    service.getBefore(key ?: 0, state.config.pageSize) // 0 или null как заглушка
                }

                LoadType.APPEND -> {
                    // Для APPEND убираем проверку на null, чтобы всегда делать запрос.
                    val remoteKeyAfter = postRemoteKeyDao.max(PostRemoteKeyEntity.KeyType.AFTER)
                    val key = remoteKeyAfter ?: state.pages.lastOrNull()?.lastOrNull()?.id

                    service.getAfter(key ?: 0, state.config.pageSize) // 0 или null как заглушка
                }
            }

            // --- Обработка ответа сервера (остается без изменений) ---
            if (!response.isSuccessful) {
                throw ApiError(response.code(), response.message())
            }
            val body = response.body() ?: throw ApiError(response.code(), response.message())

            // Флаг конца пагинации. Если сервер вернул пустой список - значит данных больше нет.
            val endOfPaginationReached = body.isEmpty()

            db.withTransaction {
                if (loadType == LoadType.REFRESH) {
                    postDao.clear()
                    postRemoteKeyDao.clear()
                }

                if (body.isNotEmpty()) {
                    postDao.insert(body.toEntity())
                }

                // Обновляем ключи только если есть данные
                if (!endOfPaginationReached) {
                    when (loadType) {
                        LoadType.REFRESH -> {
                            if (body.isNotEmpty()) {
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
                            if (body.isNotEmpty()) {
                                postRemoteKeyDao.insertOrUpdate(
                                    PostRemoteKeyEntity(
                                        type = PostRemoteKeyEntity.KeyType.BEFORE,
                                        id = body.last().id,
                                    )
                                )
                            }
                        }

                        LoadType.APPEND -> {
                            if (body.isNotEmpty()) {
                                postRemoteKeyDao.insertOrUpdate(
                                    PostRemoteKeyEntity(
                                        type = PostRemoteKeyEntity.KeyType.AFTER,
                                        id = body.first().id,
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // --- ИЗМЕНЕННАЯ ЛОГИКА ВОЗВРАТА ---
            // Возвращаем Success с флагом, который зависит ТОЛЬКО от того, был ли ответ от сервера пустым.
            // Это заставит Paging3 показывать индикаторы загрузки при PREPEND и APPEND.
            MediatorResult.Success(endOfPaginationReached = endOfPaginationReached)

        } catch (e: Exception) {
            if (e is CancellationException) {
                throw e
            }
            MediatorResult.Error(e)
        }
    }
}