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

package libgopeed;

/**
 * 自研内核（FastCore）的 JNI 绑定。
 *
 * 它和官方 Gopeed 内核是**同一套 Go 代码的不同构建**：导出的 Java 包名是 `libgopeed`
 * （官方 AAR 是 `com.gopeed.libgopeed`），并且只暴露 start/stop/configureGuard/reportSig，
 * 没有 `invokeAsync` —— 下载任务要通过 `start()` 返回的本地端口走 HTTP REST。
 *
 * 方法名与 .so 里的符号一一对应：
 * - `Java_libgopeed_Libgopeed__1init`        → `_init`
 * - `Java_libgopeed_Libgopeed_start`         → `start`
 * - `Java_libgopeed_Libgopeed_stop`          → `stop`
 * - `Java_libgopeed_Libgopeed_configureGuard`→ `configureGuard`
 * - `Java_libgopeed_Libgopeed_reportSig`     → `reportSig`
 *
 * 必须保持类名、包名、方法签名与 .so 完全一致，R8 也要 keep（见 proguard-rules.pro）。
 */
public abstract class Libgopeed {

    private static native void _init();

    /** 启动本地 Gopeed 服务，返回监听端口（0 表示失败） */
    public static native long start(String config);

    public static native void stop();

    public static native void configureGuard(String config);

    public static native void reportSig(String value);

    private Libgopeed() {
    }

    static {
        go.Seq.touch();
        _init();
    }
}
