#include "external_download_bridge.h"

#include <flutter/standard_method_codec.h>
#include <cstring>
#include <utility>
#include "external_download_request.h"
#include "utils.h"

ExternalDownloadBridge::ExternalDownloadBridge(flutter::BinaryMessenger* messenger) {
  channel_ = std::make_unique<flutter::MethodChannel<flutter::EncodableValue>>(
      messenger, "com.asterlink.app/external_open",
      &flutter::StandardMethodCodec::GetInstance());
  channel_->SetMethodCallHandler([this](const auto& call, auto result) {
    if (call.method_name() != "takeExternalOpens") {
      result->NotImplemented();
      return;
    }
    flutter::EncodableList requests;
    while (!pending_.empty()) {
      requests.emplace_back(flutter::EncodableMap{
          {flutter::EncodableValue("id"), flutter::EncodableValue("windows-" + std::to_string(++sequence_))},
          {flutter::EncodableValue("launchUri"), flutter::EncodableValue(pending_.front())},
      });
      pending_.pop_front();
    }
    result->Success(flutter::EncodableValue(std::move(requests)));
  });
  const auto initial = DownloadLaunchArgument(GetCommandLineArguments());
  if (!initial.empty()) pending_.push_back(initial);
}

ExternalDownloadBridge::~ExternalDownloadBridge() {
  channel_->SetMethodCallHandler(nullptr);
}

bool ExternalDownloadBridge::Offer(std::string value) {
  if (!IsDownloadLaunch(value) || pending_.size() >= 16 ||
      ::MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(),
                            static_cast<int>(value.size()), nullptr, 0) <= 0) {
    return false;
  }
  pending_.push_back(std::move(value));
  channel_->InvokeMethod("externalOpenAvailable", nullptr);
  return true;
}

bool ExternalDownloadBridge::AcceptCopyData(const COPYDATASTRUCT* data) {
  if (!data || data->dwData != kExternalDownloadMessage || !data->lpData ||
      data->cbData < 2 || data->cbData > kExternalDownloadMaxBytes + 1) {
    return false;
  }
  const auto* value = static_cast<const char*>(data->lpData);
  if (value[data->cbData - 1] != '\0' ||
      std::memchr(value, '\0', data->cbData - 1) != nullptr) {
    return false;
  }
  // WM_COPYDATA storage belongs to the sender and expires on return.
  return Offer(std::string(value, data->cbData - 1));
}
