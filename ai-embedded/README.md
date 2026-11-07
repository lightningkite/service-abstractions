# ai-embedded

`LlmAccess` running a model on the device: `embedded://<model-name>?path=<model>`.
See `EmbeddedLlmSettings` for every URL parameter.

| Platform | Engine | `path` |
|---|---|---|
| Android | LiteRT-LM (`litertlm-android`) | a `.litertlm` file |
| JVM (desktop) | LiteRT-LM (`litertlm-jvm`) | a `.litertlm` file |
| iOS, macOS | LiteRT-LM's C API | a `.litertlm` file |
| Browser | Transformers.js | a Hugging Face model ID or URL of ONNX files |

## JVM: Java 21

`litertlm-jvm` is compiled for Java 21 (class version 65), so this module's JVM target is too, and a desktop app using it
must run on Java 21 or newer. Only the JVM target is affected: Android, the other targets, and other modules are not.
It's purely Google's compile target; the library uses plain JNI.

Its tests need a Java 21 toolchain: `./gradlew :ai-embedded:jvmTest`.

## iOS and macOS: link LiteRT-LM yourself

LiteRT-LM's C library isn't published to Maven, so this module carries only its headers. The app links the library,
which LiteRT-LM publishes as zipped XCFrameworks on its GitHub releases (version 0.19.0, matching the headers in
`src/nativeInterop/cinterop/litertlm`):

- `CLiteRTLM.xcframework.zip`: iOS devices and Apple-silicon simulators. There's no Intel simulator build, so this
  module has no `iosX64` target.
- `CLiteRTLM_mac.xcframework.zip`: macOS.

Without it the app fails to link with undefined `_litert_lm_*` symbols.

### CocoaPods

Unzip `CLiteRTLM.xcframework` next to this podspec, then add `pod 'CLiteRTLM', :path => '<its folder>'` to the
Podfile. A Kotlin framework that uses this module should be static, so the app links LiteRT-LM's dynamic framework
only once.

```ruby
Pod::Spec.new do |spec|
  spec.name                  = 'CLiteRTLM'
  spec.version               = '0.19.0'
  spec.summary               = 'LiteRT-LM C API'
  spec.homepage              = 'https://github.com/google-ai-edge/LiteRT-LM'
  spec.license               = { :type => 'Apache-2.0' }
  spec.authors               = 'Google'
  spec.source                = { :http => '' }
  spec.ios.deployment_target = '16.0'
  spec.vendored_frameworks   = 'CLiteRTLM.xcframework'
end
```

### Swift Package Manager

Add a binary target to a local package that the app depends on:

```swift
.binaryTarget(
    name: "CLiteRTLM",
    url: "https://github.com/google-ai-edge/LiteRT-LM/releases/download/v0.19.0/CLiteRTLM.xcframework.zip",
    checksum: "b7b2450fc084058adc5185498f8b7810a9297e054a10cf55818b62e1d5f8d65d"
)
```

### Tests

The Apple test binaries need the library too; without it they're skipped. Point the build at a folder holding both
unzipped XCFrameworks:

```
./gradlew :ai-embedded:macosArm64Test -PliteRtApple=/path/to/folder
```

## Real-model tests

`LiteRtIntegrationTest` runs on the JVM and macOS when `LITERT_MODEL` names a `.litertlm` file of a model that thinks,
such as Qwen3-0.6B, and is skipped otherwise:

```
LITERT_MODEL=/path/to/Qwen3-0.6B.litertlm ./gradlew :ai-embedded:jvmTest :ai-embedded:macosArm64Test -PliteRtApple=/path/to/folder
```
