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
            // --- ИСПРАВЛЕНИЕ 1 (Оптимизация) ---
            // Получаем ID для запроса новых данных из таблицы ключей, а не из таблицы постов.
            val remoteKeyAfter = postRemoteKeyDao.max(PostRemoteKeyEntity.KeyType.AFTER)

            // Определяем, какой запрос делать на сервер
            val response = when (loadType) {
                LoadType.REFRESH -> {
                    // Если ключа AFTER нет (null), значит БД пустая
                    if (remoteKeyAfter == null) {
                        service.getLatest(state.config.initialLoadSize)
                    } else {
                        // Иначе запрашиваем посты, которые НОВЕЕ тех, что есть в БД
                        service.getAfter(remoteKeyAfter, state.config.pageSize)
                    }
                }

                LoadType.PREPEND -> {
                    // Отключаем автоматическую догрузку вверх
                    return MediatorResult.Success(endOfPaginationReached = true)
                }

                LoadType.APPEND -> {
                    // Обычная догрузка вниз
                    val id = postRemoteKeyDao.min(PostRemoteKeyEntity.KeyType.BEFORE)
                        ?: return MediatorResult.Success(endOfPaginationReached = false)
                    service.getBefore(id, state.config.pageSize)
                }
            }

            if (!response.isSuccessful) {
                throw ApiError(response.code(), response.message())
            }
            val body = response.body() ?: throw ApiError(response.code(), response.message())

            // Если новых данных от сервера нет
            if (body.isEmpty()) {
                return MediatorResult.Success(endOfPaginationReached = true)
            }

            db.withTransaction {
                when (loadType) {
                    LoadType.REFRESH -> {
                        // Обновляем ключ для REFRESH (AFTER)
                        postRemoteKeyDao.insertOrUpdate(
                            PostRemoteKeyEntity(
                                type = PostRemoteKeyEntity.KeyType.AFTER,
                                id = body.first().id,
                            )
                        )

                        // --- ИСПРАВЛЕНИЕ 2 (Инициализация APPEND) ---
                        // Если это первая загрузка (когда мы не нашли ключ AFTER),
                        // инициализируем ключ BEFORE, чтобы можно было листать вниз.
                        if (remoteKeyAfter == null) {
                            postRemoteKeyDao.insertOrUpdate(
                                PostRemoteKeyEntity(
                                    type = PostRemoteKeyEntity.KeyType.BEFORE,
                                    id = body.last().id,
                                )
                            )
                        }
                    }

                    LoadType.PREPEND -> {
                        // Этот блок не выполнится
                    }

                    LoadType.APPEND -> {
                        // Сохраняем новый ключ для следующей догрузки вниз (BEFORE)
                        postRemoteKeyDao.insertOrUpdate(
                            PostRemoteKeyEntity(
                                type = PostRemoteKeyEntity.KeyType.BEFORE,
                                id = body.last().id,
                            )
                        )
                    }
                }
                // Сохраняем полученные посты в основную таблицу
                postDao.insert(body.toEntity())
            }

            MediatorResult.Success(endOfPaginationReached = false)

        } catch (e: Exception) {
            if (e is CancellationException) {
                throw e
            }
            MediatorResult.Error(e)
        }
    }
}