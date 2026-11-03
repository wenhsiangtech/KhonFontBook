// KhonFontBook decoding entry, under the project's GPL-3.0 license.
// Actual WOFF2 decoding is provided by the upstream MIT implementation.
#include <cstdint>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>
#include <woff2/decode.h>
#include <woff2/output.h>

namespace {
constexpr std::size_t input_limit = 20U * 1024U * 1024U;
constexpr std::size_t output_limit = 64U * 1024U * 1024U;
std::uint32_t u32(const std::vector<std::uint8_t>& bytes, std::size_t at) {
  return (static_cast<std::uint32_t>(bytes[at]) << 24U) |
         (static_cast<std::uint32_t>(bytes[at + 1]) << 16U) |
         (static_cast<std::uint32_t>(bytes[at + 2]) << 8U) |
         static_cast<std::uint32_t>(bytes[at + 3]);
}
}  // namespace

int main(int argc, char** argv) {
  if (argc == 2 && std::string(argv[1]) == "--version") {
    std::cout << "KFB-WOFF2/1\n";
    return 0;
  }
  if (argc != 3) return 2;
  try {
    std::ifstream input(argv[1], std::ios::binary | std::ios::ate);
    const auto size = input.tellg();
    if (size < 48 || size > static_cast<std::streamoff>(input_limit)) return 2;
    input.seekg(0);
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(size));
    if (!input.read(reinterpret_cast<char*>(bytes.data()), size)) return 2;
    const auto tables = (static_cast<unsigned>(bytes[12]) << 8U) | bytes[13];
    const auto flavor = u32(bytes, 4);
    if (u32(bytes, 0) != 0x774f4632U || u32(bytes, 8) != bytes.size() ||
        bytes[14] != 0 || bytes[15] != 0 || tables < 1 || tables > 256 ||
        (flavor != 0x00010000U && flavor != 0x4f54544fU) ||
        u32(bytes, 16) > output_limit) return 2;
    std::string decoded;
    woff2::WOFF2StringOut output(&decoded);
    output.SetMaxSize(output_limit);
    if (!woff2::ConvertWOFF2ToTTF(bytes.data(), bytes.size(), &output)) return 2;
    if (output.Size() > output_limit) return 2;
    std::ofstream file(argv[2], std::ios::binary | std::ios::trunc);
    file.write(decoded.data(), static_cast<std::streamsize>(output.Size()));
    return file.good() ? 0 : 2;
  } catch (const std::exception&) {
    return 3;
  }
}
