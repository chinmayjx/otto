#!/bin/sh
# Builds the echo canceller (libotto_echo.so) into ../jniLibs for the phone's ABIs. Any clang will do (the NDK's own
# toolchain only runs on x86-64 hosts): it compiles against the NDK's sysroot, which is the same on every host.
#   NDK=~/android-sdk/ndk/<version> ./build.sh
set -e
cd "$(dirname "$0")"
NDK=${NDK:-$(ls -d "$HOME"/android-sdk/ndk/* | sort | tail -1)}
SYSROOT=$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot
API=31
SRC="echo.c speexdsp/mdf.c speexdsp/preprocess.c speexdsp/filterbank.c speexdsp/fftwrap.c speexdsp/kiss_fft.c speexdsp/kiss_fftr.c"
RT=$(ls -d "$NDK"/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/*/lib/linux | tail -1)
for pair in arm64-v8a:aarch64-linux-android:aarch64 x86_64:x86_64-linux-android:x86_64; do
  abi=${pair%%:*}; rest=${pair#*:}; triple=${rest%%:*}; arch=${rest#*:}
  mkdir -p ../jniLibs/$abi
  clang --target=$triple$API --sysroot="$SYSROOT" -fuse-ld=lld -shared -fPIC -O2 -DFLOATING_POINT -DUSE_KISS_FFT -DEXPORT= \
    -Ispeexdsp -Ispeexdsp/include -Wno-unused-parameter -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 \
    -nodefaultlibs -o ../jniLibs/$abi/libotto_echo.so $SRC -lc -lm "$RT/libclang_rt.builtins-$arch-android.a"
  echo "built ../jniLibs/$abi/libotto_echo.so"
done
