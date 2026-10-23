#include "player_image_bridge.h"
#include "player_image_data.h"
#include <flutter/standard_method_codec.h>

PlayerImageBridge::PlayerImageBridge(flutter::BinaryMessenger* messenger, HWND owner) {
  channel_ = std::make_unique<flutter::MethodChannel<flutter::EncodableValue>>(
      messenger, "com.asterlink.app/player_images", &flutter::StandardMethodCodec::GetInstance());
  channel_->SetMethodCallHandler([owner](const auto& call, auto result) {
    if (call.method_name() != "copyImage") { result->NotImplemented(); return; }
    const auto* args = call.arguments()
        ? std::get_if<flutter::EncodableMap>(call.arguments()) : nullptr;
    const std::vector<uint8_t>* rgba = nullptr;
    int width = 0, height = 0;
    if (args) {
      const auto w = args->find(flutter::EncodableValue("width"));
      const auto h = args->find(flutter::EncodableValue("height"));
      const auto data = args->find(flutter::EncodableValue("rgba"));
      if (w != args->end()) {
        if (const auto* value = std::get_if<int32_t>(&w->second)) width = *value;
      }
      if (h != args->end()) {
        if (const auto* value = std::get_if<int32_t>(&h->second)) height = *value;
      }
      if (data != args->end()) rgba = std::get_if<std::vector<uint8_t>>(&data->second);
    }
    const auto dib = rgba ? PlayerImageDib(*rgba, width, height) : std::vector<uint8_t>{};
    if (dib.empty()) { result->Error("image", "截图数据无效"); return; }
    HGLOBAL memory = GlobalAlloc(GMEM_MOVEABLE, dib.size());
    void* destination = memory ? GlobalLock(memory) : nullptr;
    if (!destination) {
      if (memory) GlobalFree(memory);
      result->Error("memory", "内存不足，无法复制截图"); return;
    }
    std::memcpy(destination, dib.data(), dib.size());
    GlobalUnlock(memory);
    if (!OpenClipboard(owner)) {
      GlobalFree(memory);
      result->Error("clipboard", "剪贴板正忙，请重试"); return;
    }
    const bool copied = EmptyClipboard() && SetClipboardData(CF_DIB, memory) != nullptr;
    CloseClipboard();
    if (!copied) {
      GlobalFree(memory);
      result->Error("clipboard", "复制图片失败，请重试"); return;
    }
    // Windows owns memory after successful SetClipboardData.
    result->Success();
  });
}

PlayerImageBridge::~PlayerImageBridge() {
  channel_->SetMethodCallHandler(nullptr);
}
