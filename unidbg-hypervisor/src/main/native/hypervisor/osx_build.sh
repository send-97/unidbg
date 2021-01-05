JAVA_INC="$(realpath "$JAVA_HOME"/include)"
JAVA_PLATFORM_INC="$(dirname "$(find "$JAVA_INC" -name jni_md.h)")"

"$(/usr/libexec/java_home -v 1.8)"/bin/javah -cp ../../../../../unidbg-api/target/classes com.github.unidbg.arm.backend.hypervisor.Hypervisor && \
  xcrun -sdk macosx clang++ -m64 -o libhypervisor.dylib -shared -std=c++17 -O2 -mmacosx-version-min=10.9 \
  -framework Hypervisor hypervisor.cpp \
  -I "$JAVA_INC" -I "$JAVA_PLATFORM_INC" && \
  codesign --entitlements hypervisor.entitlements -s - libhypervisor.dylib && \
  mv libhypervisor.dylib ../../resources/natives/osx_arm64
