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

package com.jiexiy.app.data.network

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 阿里云盘 Web 设备协议的 secp256k1 签名（对应 wenxi `aliyun_signature.dart`）。
 *
 * 阿里云盘的设备会话创建需要一次「已预哈希」的 ECDSA 签名：签名输入是
 * `sha256(appId:deviceId:userId:nonce)` 的 32 字节摘要，而不是原文，
 * 因此不能用 `Signature("SHA256withECDSA")`（它会再哈希一次）。
 * 这里用纯 JVM 大数实现曲线运算，避免引入 Pointycastle / BouncyCastle 依赖。
 */
object AliyunSignature {

    /** Web 端 app 标识，参与签名摘要。 */
    const val APP_ID = "5dde4e1bdf9e4966b387ba58f4b3fdc3"

    // secp256k1 曲线参数
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val G = ECPoint(
        BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
        BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    )

    /** 一次签名的完整结果：签名串 + 由私钥派生的公钥（未压缩格式去掉 0x04 前缀的 x||y 十六进制）。 */
    data class Signed(val signature: String, val publicKey: String)

    /** 生成设备私钥：32 字节随机数，取值必须落在 (0, n) 内。 */
    fun privateKey(): String {
        val random = SecureRandom()
        while (true) {
            val raw = ByteArray(32).also(random::nextBytes)
            val value = BigInteger(1, raw)
            if (value.signum() > 0 && value < N) return formatHex(value, 64)
        }
    }

    /**
     * 对已预哈希的摘要做 ECDSA 签名。
     * @param privateKey 64 位十六进制私钥
     * @param deviceId 设备 id
     * @param userId 用户 id
     * @param nonce 签名序号，web 端固定传 0
     */
    fun sign(
        privateKey: String,
        deviceId: String,
        userId: String,
        nonce: Int = 0
    ): Signed {
        require(
            privateKey.length == 64 &&
                privateKey.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        ) { "阿里设备密钥格式无效，请重新登录" }
        val d = BigInteger(1, hexToBytes(privateKey))
        require(d.signum() > 0 && d < N) { "阿里设备密钥无效，请重新登录" }

        val digest = sha256("$APP_ID:$deviceId:$userId:$nonce")
        val z = BigInteger(1, digest)

        // 公钥只依赖 d，与签名随机数无关，算一次即可
        val q = scalarMul(G, d) ?: return Signed("", "")
        val publicKeyHex = formatHex(q.x, 64) + formatHex(q.y, 64)

        val random = SecureRandom()
        val halfN = N.shiftRight(1)
        while (true) {
            val k = BigInteger(N.bitLength(), random).mod(N)
            if (k.signum() <= 0) continue
            val rPoint = scalarMul(G, k) ?: continue
            val r = rPoint.x.mod(N)
            if (r.signum() == 0) continue
            val s = k.modInverse(N).multiply(z.add(r.multiply(d))).mod(N)
            if (s.signum() == 0) continue
            // 低 S 规范化，避免同一签名有两种形式
            val sLow = if (s > halfN) N.subtract(s) else s
            if (sLow.signum() == 0) continue

            return Signed(
                formatHex(r, 64) + formatHex(sLow, 64) +
                    formatHex(BigInteger.valueOf(recoveryBit(q, r, sLow, z).toLong()), 2),
                publicKeyHex
            )
        }
    }

    /** 由签名 (r, s) 与公钥反推恢复位：y 为奇数置 bit0，x ≥ n 置 bit1。 */
    private fun recoveryBit(q: ECPoint, r: BigInteger, sLow: BigInteger, z: BigInteger): Int {
        val sInv = sLow.modInverse(N)
        val p1 = scalarMul(G, z.multiply(sInv).mod(N))
        val p2 = scalarMul(q, r.multiply(sInv).mod(N))
        val point = p1?.plus(p2) ?: return 0
        return (if (point.y.testBit(0)) 1 else 0) or (if (point.x >= N) 2 else 0)
    }

    fun sha256(text: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    // ---------- 曲线运算 ----------

    private data class ECPoint(val x: BigInteger, val y: BigInteger) {
        /** 曲线点加法。重合点走倍点公式；无穷远返回 null。 */
        operator fun plus(other: ECPoint?): ECPoint? = when {
            other == null -> null
            x == other.x && y != other.y -> null
            this == other -> {
                val slope = BigInteger("3")
                    .multiply(x.multiply(x))
                    .multiply(y.multiply(y).shiftRight(1).modInverse(P))
                    .mod(P)
                addWithSlope(slope, other)
            }
            else -> {
                val slope = other.y.subtract(y)
                    .multiply(other.x.subtract(x).modInverse(P))
                    .mod(P)
                addWithSlope(slope, other)
            }
        }

        private fun addWithSlope(slope: BigInteger, other: ECPoint): ECPoint {
            val x3 = slope.multiply(slope).subtract(x).subtract(other.x).mod(P)
            val y3 = slope.multiply(x.subtract(x3)).subtract(y).mod(P)
            return ECPoint(x3, y3)
        }
    }

    /** 标量乘：双倍-加法。标量为 0 时返回无穷远（null）。 */
    private fun scalarMul(point: ECPoint, scalar: BigInteger): ECPoint? {
        if (scalar.signum() <= 0) return null
        var result: ECPoint? = null
        var current: ECPoint = point
        var k = scalar
        while (k.signum() > 0) {
            if (k.testBit(0)) result = result?.plus(current)
            current = current.plus(current) ?: return null
            k = k.shiftRight(1)
        }
        return result
    }

    // ---------- 十六进制工具 ----------

    private fun formatHex(value: BigInteger, width: Int): String {
        val raw = value.toString(16)
        return if (raw.length >= width) raw else "0".repeat(width - raw.length) + raw
    }

    private fun hexToBytes(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16).toByte()
        }
        return bytes
    }
}
