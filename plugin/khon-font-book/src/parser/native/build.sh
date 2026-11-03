#!/bin/sh
# Build only; the installed plugin never invokes this script or downloads tools.
set -eu
base=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
out=$(CDPATH='' cd -- "$1" && pwd)
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
cd "$build"
for src in "$base"/brotli/c/common/*.c "$base"/brotli/c/dec/*.c; do
 name=${src##*/}
 gcc -O2 -DBROTLI_BUILD_PORTABLE -DBROTLI_STATIC_COMPILATION -I"$base/brotli/c/include" -c "$src" -o "${name%.c}.o"
done
g++ -static-libstdc++ -static-libgcc -O2 -Wall -Wextra -Wpedantic -std=c++20 -DWOFF2_FORCE_PORTABLE \
 -I"$base/woff2/src" -I"$base/woff2/include" -I"$base/brotli/c/include" \
 "$base/FontDecoder.cc" "$base/woff2/src/woff2_dec.cc" "$base/woff2/src/woff2_common.cc" \
 "$base/woff2/src/woff2_out.cc" "$base/woff2/src/table_tags.cc" "$base/woff2/src/variable_length.cc" \
 ./*.o -Wl,-Map,"$out/decoder.map" -o decoder
strip decoder
cp decoder "$out/decoder"
readelf -d decoder > "$out/dependencies.txt"
readelf --version-info decoder > "$out/versions.txt"
