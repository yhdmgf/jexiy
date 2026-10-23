#ifndef RUNNER_PLAYER_IMAGE_BRIDGE_H_
#define RUNNER_PLAYER_IMAGE_BRIDGE_H_
#include <windows.h>
#include <flutter/encodable_value.h>
#include <flutter/method_channel.h>
#include <memory>

class PlayerImageBridge {
 public:
  PlayerImageBridge(flutter::BinaryMessenger* messenger, HWND owner);
  ~PlayerImageBridge();
 private:
  std::unique_ptr<flutter::MethodChannel<flutter::EncodableValue>> channel_;
};
#endif
