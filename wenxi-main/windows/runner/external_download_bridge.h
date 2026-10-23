#ifndef RUNNER_EXTERNAL_DOWNLOAD_BRIDGE_H_
#define RUNNER_EXTERNAL_DOWNLOAD_BRIDGE_H_

#include <windows.h>
#include <flutter/binary_messenger.h>
#include <flutter/encodable_value.h>
#include <flutter/method_channel.h>
#include <deque>
#include <cstdint>
#include <memory>
#include <string>

class ExternalDownloadBridge {
 public:
  explicit ExternalDownloadBridge(flutter::BinaryMessenger* messenger);
  ~ExternalDownloadBridge();
  bool AcceptCopyData(const COPYDATASTRUCT* data);

 private:
  bool Offer(std::string value);
  std::unique_ptr<flutter::MethodChannel<flutter::EncodableValue>> channel_;
  std::deque<std::string> pending_;
  int64_t sequence_ = 0;
};

#endif
