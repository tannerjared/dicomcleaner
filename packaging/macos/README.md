# Apple Silicon macOS Packaging

DicomCleaner is a Java/Swing application. Native Apple Silicon support does not require a Python rewrite: build the existing application with an arm64 JDK and package it with `jpackage`, which creates a macOS `.app` launcher and bundles the matching arm64 Java runtime.

The packaged app launches the local-only cleaner. It accepts a local DICOM file or folder and writes cleaned output to a local file or folder. The old network query/retrieve and Google import/export workflow is not exposed through this launcher.

The current bundled dependencies are jar files and do not contain `.dylib`, `.jnilib`, `.so`, or `.dll` native libraries. That means the runtime architecture is controlled by the JDK used for packaging.

## Requirements

- Apple Silicon Mac
- Apple Silicon JDK 21 or newer, with `java`, `javac`, and `jpackage`
- Apache Ant

Verify the active JDK is native:

```sh
uname -m
java -XshowSettings:properties -version 2>&1 | grep 'os.arch'
```

The machine should report `arm64`, and Java should report `os.arch = aarch64`.

## Build

```sh
./packaging/macos/build-apple-silicon-app.sh
open dist/macos/DicomCleaner.app
```

For command-line use:

```sh
dist/macos/DicomCleaner.app/Contents/MacOS/DicomCleaner /path/to/input /path/to/output
```

When the input is a single DICOM file, use an output path ending in `.dcm` to
write one specific output file. Otherwise the output path is treated as a folder.

To produce a disk image instead of an app image:

```sh
./packaging/macos/build-apple-silicon-app.sh --type dmg
```

To reuse an already built jar:

```sh
./packaging/macos/build-apple-silicon-app.sh --skip-build
```

## Signing

Signing is optional for local development and required for smooth distribution outside your own machine. If a Developer ID certificate is available:

```sh
MAC_SIGN=1 MAC_SIGNING_KEY_USER_NAME="Developer ID Application: Example Team" \
  ./packaging/macos/build-apple-silicon-app.sh --type dmg
```

Notarization is intentionally left as a release step because it depends on Apple developer account credentials.

The build script also handles a common macOS signing failure caused by extended attributes in an app bundle: it clears those attributes from the generated `.app` and retries the app signature for `app-image` builds.

## GitHub Actions

The `Apple Silicon macOS app` workflow builds on GitHub's arm64 macOS runner, verifies that Java is running as `aarch64`, packages the app, and uploads a zipped `.app` artifact.
