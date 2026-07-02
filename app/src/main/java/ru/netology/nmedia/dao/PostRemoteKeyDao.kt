package ru.netology.nmedia.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ru.netology.nmedia.entity.PostRemoteKeyEntity

@Dao
interface PostRemoteKeyDao {

    @Query("SELECT COUNT(*) == 0 FROM PostRemoteKeyEntity")
    suspend fun isEmpty(): Boolean


    @Query("SELECT MAX(id) FROM PostRemoteKeyEntity WHERE type = :keyType")
    suspend fun max(keyType: PostRemoteKeyEntity.KeyType): Long?
    @Query("SELECT MIN(id) FROM PostRemoteKeyEntity WHERE type = :keyType")
    suspend fun min(keyType: PostRemoteKeyEntity.KeyType): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(key: PostRemoteKeyEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: PostRemoteKeyEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(keys: List<PostRemoteKeyEntity>)

    @Query("DELETE FROM PostRemoteKeyEntity")
    suspend fun removeAll()

    @Query("DELETE FROM PostRemoteKeyEntity")
    suspend fun clear()
}