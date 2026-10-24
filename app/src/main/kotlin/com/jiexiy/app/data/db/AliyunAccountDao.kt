package com.jiexiy.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AliyunAccountDao {
    @Query("SELECT * FROM aliyun_account ORDER BY isPrimary DESC, updatedAt DESC LIMIT 1")
    fun observeAccount(): Flow<AliyunAccountEntity?>

    @Query("SELECT * FROM aliyun_account ORDER BY isPrimary DESC, updatedAt DESC LIMIT 1")
    suspend fun getAccount(): AliyunAccountEntity?

    @Query("SELECT * FROM aliyun_account ORDER BY isPrimary DESC, updatedAt DESC")
    fun observeAccounts(): Flow<List<AliyunAccountEntity>>

    @Query("SELECT * FROM aliyun_account ORDER BY isPrimary DESC, updatedAt DESC")
    suspend fun getAccounts(): List<AliyunAccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AliyunAccountEntity)

    @Query("UPDATE aliyun_account SET isPrimary = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun setPrimary(id: String)

    @Query("UPDATE aliyun_account SET remark = :remark WHERE id = :id")
    suspend fun updateRemark(id: String, remark: String)

    @Query("UPDATE aliyun_account SET accessToken = :accessToken, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateAccessToken(id: String, accessToken: String, updatedAt: Long)

    @Query("DELETE FROM aliyun_account WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM aliyun_account")
    suspend fun clear()
}
