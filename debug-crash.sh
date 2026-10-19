#!/bin/bash
# 解析云崩溃诊断脚本
# 用法：./debug-crash.sh
# 前提：手机已开启 USB 调试并连接电脑，电脑已安装 adb

set -e

PACKAGE="com.jiexiy.app"
LOG_FILE="crash-diagnosis.log"

echo "=========================================="
echo "  解析云 崩溃诊断工具"
echo "=========================================="
echo ""

# 检查 adb
if ! command -v adb &> /dev/null; then
    echo "❌ 错误：未找到 adb 命令"
    echo "   请先安装 Android SDK Platform Tools"
    echo "   下载地址：https://developer.android.com/tools/releases/platform-tools"
    exit 1
fi

# 检查设备
echo "=== 连接的设备 ==="
adb devices
echo ""

DEVICE=$(adb devices | grep -v "List" | grep "device" | head -1 | awk '{print $1}')
if [ -z "$DEVICE" ]; then
    echo "❌ 错误：未检测到设备"
    echo "   请确保手机已开启 USB 调试并授权此电脑"
    exit 1
fi
echo "✅ 检测到设备: $DEVICE"
echo ""

# 获取安卓版本
ANDROID_VERSION=$(adb -s $DEVICE shell getprop ro.build.version.release)
SDK_VERSION=$(adb -s $DEVICE shell getprop ro.build.version.sdk)
echo "📱 设备信息: Android $ANDROID_VERSION (API $SDK_VERSION)"
echo ""

# 清除旧日志
echo "🧹 清除旧日志..."
adb -s $DEVICE logcat -c
echo ""

# 启动应用（如果有入口）
echo "🚀 启动应用..."
adb -s $DEVICE shell monkey -p $PACKAGE -c android.intent.category.LAUNCHER 1 &
MONKEY_PID=$!

# 等待应用启动
sleep 3

# 抓取日志（后台运行，前台输出到文件）
echo "📋 抓取日志（15秒）..."
LOGCAT_START=$(date +%s)
adb -s $DEVICE logcat -d -s "jihexiy" "AndroidRuntime" "PackageManager" "ActivityManager" "dalvikvm" > "$LOG_FILE" 2>&1 &
LOGCAT_PID=$!

# 等待日志抓取
sleep 15
kill $LOGCAT_PID 2>/dev/null || true
wait $LOGCAT_PID 2>/dev/null || true

# 杀死 monkey
kill $MONKEY_PID 2>/dev/null || true

echo ""
echo "=========================================="
echo "  日志分析结果"
echo "=========================================="
echo ""

# 检查崩溃
if grep -q "FATAL EXCEPTION" "$LOG_FILE"; then
    echo "⚠️  检测到崩溃！"
    echo ""
    echo "--- AndroidRuntime 崩溃栈 ---"
    grep -A 50 "FATAL EXCEPTION" "$LOG_FILE" | head -60
    echo ""
    echo "--- RuntimeException / Error ---"
    grep -B 5 -A 20 "RuntimeException\|Error:" "$LOG_FILE" | head -80
    echo ""
else
    echo "✅ 未检测到 FATAL EXCEPTION"
fi

echo ""
echo "=== jihexiy 自定义日志（最近 50 行）==="
grep "jihexiy" "$LOG_FILE" | tail -50
echo ""

echo "=== PackageManager 相关 ==="
grep -i "package\|provider\|manifest" "$LOG_FILE" | tail -30
echo ""

echo "=== ActivityManager Kill 记录 ==="
grep "ActivityManager" "$LOG_FILE" | grep -E "CRASH|Kill\|Force stop" | tail -20
echo ""

# 检查应用安装信息
echo "=========================================="
echo "  应用信息"
echo "=========================================="
echo ""
echo "📦 安装状态:"
adb -s $DEVICE pm list packages | grep $PACKAGE
echo ""

if adb -s $DEVICE shell pm path $PACKAGE 2>/dev/null | grep -q "$PACKAGE"; then
    echo "✅ 应用已安装"
    echo ""
    echo "📋 版本信息:"
    adb -s $DEVICE dumpsys package $PACKAGE | grep -E "versionName|versionCode" | head -5
    echo ""
    echo "📅 安装时间:"
    adb -s $DEVICE dumpsys package $PACKAGE | grep -E "firstInstallTime|lastUpdateTime" | head -3
else
    echo "❌ 应用未安装！请先安装 APK"
fi

echo ""
echo "=========================================="
echo "  诊断完成"
echo "=========================================="
echo ""
echo "📄 完整日志已保存到: $LOG_FILE"
echo ""
echo "下一步建议:"
echo "  1. 查看上面的分析结果"
echo "  2. 如果找到崩溃栈，把内容发给我"
echo "  3. 或者把 $LOG_FILE 文件发给我"
echo ""
