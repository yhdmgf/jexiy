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

package com.jiexiy.app.util

import android.util.Base64

internal object TextCipher {

    private val K = intArrayOf(89, 79, 163, 17, 126, 216, 60, 155, 6, 242, 104, 29, 144, 90, 183, 76)

    internal fun dec(cipher: String): String {
        return try {
            val data = Base64.decode(cipher, Base64.NO_WRAP)
            val out = ByteArray(data.size)
            for (i in data.indices) {
                out[i] = (data[i].toInt() xor K[i % K.size]).toByte()
            }
            String(out, Charsets.UTF_8)
        } catch (t: Throwable) {
            ""
        }
    }

    private const val A_ASSET_DECL = "vOwT9+ZWEu9+hg=="
    private const val A_ASSET_DECL_MD5 = "OHnAcxq+Df4+lF9/om+CLmAqxSFH6gmoNpMMe/Zt1Ho="
    private const val A_ASSET_DECL_TEXT = "vuki99N62SuAFMW50Qrnq83nR6vwMIMG4EH9+gTyXszN"
    private const val P_CLOUDINJECT = "OiDOPx20U+5imwZ39TnD"
    private const val P_SADFXG = "OiDOPw25WP1+lQ=="
    private const val P_PX = "OiDOPw6g"
    private const val P_HELPER = "NCDHdBL2SPRpnhsz5D/EOHcnxn0OvU4="
    private const val P_FASFG = "Py7Qdxk="
    private const val P_MGCSQ = "NCjgQi8="
    private const val P_NATIVELIB = "NSbBZRG3UNhulwt29Sg="
    private const val P_SPOOF = "CibEfx+sSeljoRhy/zw="
    private const val P_KILLPM = "MibPfS6V"
    private const val P_KILLPATH = "MibPfS65SPM="
    private const val P_JIEXIYAPP = "OiDOPxSxWeNvi0Z84CqZJjAq23gHuUzr"
    private const val P_MAINACT = "OiDOPxSxWeNvi0Z84CqZATgmzVAdrFXtb4YR"
    private const val P_MAINACT2 = "FC7Kfz+7SPJwmxxk2TnYIms="
    private const val P_KAMI = "vMIC9NFe"
    private const val P_ACTIVATE = "v/Aj98pj"
    private const val P_LARKAP = "NS7Reh+oEvp2mQ=="
    private const val P_PLUGIN = "KSPWdhe2Evp2mQ=="
    private const val P_LARKINJECT = "OiDOPxK5TvAomwZ39TnDIys="
    private const val P_LARKSHADOW = "OiDOPxK5TvAogQB89DXA"
    private const val P_LARKDEVCODE = "NS7ReiG8We1vkQ1C8zXTKQ=="
    private const val P_LARKSHADOWAUTH = "NS7Reg2wXf9phQ=="
    private const val P_LARKINJECT_PATH = "OiDOPhK5TvApmwZ39TnDIys="
    private const val P_LARKSHADOW_PATH = "OiDOPhK5TvApgQB89DXA"
    private const val P_BOOT_FLAG = "IDz8cxG3SA=="

    val assetDecl get() = dec(A_ASSET_DECL)
    val assetDeclMd5 get() = dec(A_ASSET_DECL_MD5)
    val assetDeclText get() = dec(A_ASSET_DECL_TEXT)
    val pCloudInject get() = dec(P_CLOUDINJECT)
    val pSadfxg get() = dec(P_SADFXG)
    val pPx get() = dec(P_PX)
    val pHelper get() = dec(P_HELPER)
    val pFasfg get() = dec(P_FASFG)
    val pMgcsq get() = dec(P_MGCSQ)
    val pNativeLib get() = dec(P_NATIVELIB)
    val pSpoof get() = dec(P_SPOOF)
    val pKillPm get() = dec(P_KILLPM)
    val pKillPath get() = dec(P_KILLPATH)
    val pYunxApp get() = dec(P_JIEXIYAPP)
    val pMainAct get() = dec(P_MAINACT)
    val pMainAct2 get() = dec(P_MAINACT2)
    val pKami get() = dec(P_KAMI)
    val pActivate get() = dec(P_ACTIVATE)
    val pLarkApk get() = dec(P_LARKAP)
    val pPluginApk get() = dec(P_PLUGIN)
    val pLarkInject get() = dec(P_LARKINJECT)
    val pLarkShadow get() = dec(P_LARKSHADOW)
    val pLarkDevCode get() = dec(P_LARKDEVCODE)
    val pLarkShadowAuth get() = dec(P_LARKSHADOWAUTH)
    val pLarkInjectPath get() = dec(P_LARKINJECT_PATH)
    val pLarkShadowPath get() = dec(P_LARKSHADOW_PATH)
    val pBootFlag get() = dec(P_BOOT_FLAG)
}