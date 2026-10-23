#ifndef RUNNER_PLAYER_IMAGE_DATA_H_
#define RUNNER_PLAYER_IMAGE_DATA_H_

#include <windows.h>
#include <cstdint>
#include <cstring>
#include <vector>

// Separate from clipboard I/O so validation and channel order can be tested
// without replacing the user's clipboard contents.
inline std::vector<uint8_t> PlayerImageDib(const std::vector<uint8_t>& rgba,
                                         int width, int height) {
  if (width <= 0 || height <= 0 || width > 8192 || height > 8192) return {};
  const size_t pixels = static_cast<size_t>(width) * height;
  if (pixels > 16 * 1024 * 1024 || rgba.size() != pixels * 4) return {};
  BITMAPINFOHEADER header{};
  header.biSize = sizeof(header);
  header.biWidth = width;
  header.biHeight = -height;  // Top-down rows, matching Flutter rawRgba.
  header.biPlanes = 1;
  header.biBitCount = 32;
  header.biCompression = BI_RGB;
  header.biSizeImage = static_cast<DWORD>(rgba.size());
  std::vector<uint8_t> dib(sizeof(header) + rgba.size());
  std::memcpy(dib.data(), &header, sizeof(header));
  for (size_t i = 0; i < rgba.size(); i += 4) {
    dib[sizeof(header) + i] = rgba[i + 2];
    dib[sizeof(header) + i + 1] = rgba[i + 1];
    dib[sizeof(header) + i + 2] = rgba[i];
    dib[sizeof(header) + i + 3] = rgba[i + 3];
  }
  return dib;
}
#endif
