#include "../player_image_data.h"
#include <cassert>

int main() {
  const std::vector<uint8_t> pixels = {255, 0, 0, 255, 0, 128, 255, 255};
  const auto dib = PlayerImageDib(pixels, 1, 2);
  assert(dib.size() == sizeof(BITMAPINFOHEADER) + 8);
  BITMAPINFOHEADER header{};
  std::memcpy(&header, dib.data(), sizeof(header));
  assert(header.biWidth == 1 && header.biHeight == -2);
  assert(header.biBitCount == 32 && header.biSizeImage == 8);
  const auto offset = sizeof(header);
  assert(dib[offset] == 0 && dib[offset + 2] == 255);
  assert(dib[offset + 4] == 255 && dib[offset + 5] == 128 && dib[offset + 6] == 0);
  assert(PlayerImageDib({}, 0, 0).empty());
  assert(PlayerImageDib(pixels, 2, 2).empty());
  assert(PlayerImageDib(pixels, 8192, 8192).empty());
  assert(PlayerImageDib(pixels, -1, 2).empty());
  return 0;
}
