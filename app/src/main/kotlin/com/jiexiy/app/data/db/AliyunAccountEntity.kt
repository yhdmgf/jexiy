package com.jiexiy.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "aliyun_account")
data class AliyunAccountEntity(
    @PrimaryKey
    override val id: String = "aliyun",
    val accessToken: String = "",
    val refreshToken: String = "",
    val deviceId: String = "",
    val devicePrivateKey: String = "",
    val userId: String = "",
    val driveId: String = "",
    val expiresAtMs: Long = 0L,
    override val nickname: String = "",
    override val updatedAt: Long = System.currentTimeMillis(),
    override val isPrimary: Boolean = false,
    override val remark: String = ""
) : MultiAccountEntity
