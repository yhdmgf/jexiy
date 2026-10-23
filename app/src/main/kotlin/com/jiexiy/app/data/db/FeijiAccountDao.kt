/*
 * jexiy (jexiy) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 jexiy
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.jiexiy.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FeijiAccountDao {

    @Query("SELECT * FROM feiji_account ORDER BY isPrimary DESC, updatedAt DESC LIMIT 1")
    fun observeAccount(): Flow<FeijiAccountEntity?>

    @Query("SELECT * FROM feiji_account ORDER BY isPrimary DESC, updatedAt DESC LIMIT 1")
    suspend fun getAccount(): FeijiAccountEntity?

    @Query("SELECT * FROM feiji_account ORDER BY isPrimary DESC, updatedAt DESC")
    fun observeAccounts(): Flow<List<FeijiAccountEntity>>

    @Query("SELECT * FROM feiji_account ORDER BY isPrimary DESC, updatedAt DESC")
    suspend fun getAccounts(): List<FeijiAccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: FeijiAccountEntity)

    @Query("UPDATE feiji_account SET isPrimary = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun setPrimary(id: String)

    @Query("UPDATE feiji_account SET remark = :remark WHERE id = :id")
    suspend fun updateRemark(id: String, remark: String)

    @Query("DELETE FROM feiji_account WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM feiji_account")
    suspend fun clear()
}
