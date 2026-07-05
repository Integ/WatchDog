# GitHub Actions for the iOS Viewer

GitHub Actions can help with the iOS viewer, but there are two different build
targets:

1. Hosted macOS runner validation.
2. Real iPod touch 4 / iOS 6 / armv7 builds.

## Hosted Runner

The workflow at `.github/workflows/ios-viewer.yml` runs a validation job on
GitHub's hosted `macos-15` runner. This job checks:

- `Info.plist`
- `project.pbxproj`
- Whether `xcodebuild` can inspect the Xcode project

This is useful and should work on normal pushes and pull requests.

## Modern Smoke Build

A manual workflow dispatch option named `hosted-modern-build` tries to compile
the app with the hosted runner's modern Xcode and simulator SDK.

This does not prove iPod touch 4 compatibility. It only proves that the current
Objective-C app still compiles against a modern SDK when the deployment target is
temporarily raised for CI.

## Legacy iOS 6 Build

To build the real target, use the manual workflow dispatch option named
`self-hosted-legacy-build`.

That job expects a self-hosted runner with these labels:

```text
self-hosted, macOS, xcode-legacy
```

The machine behind that runner must provide an old Xcode/iOS SDK combination
that can build:

- `IPHONEOS_DEPLOYMENT_TARGET=6.0`
- `ARCHS=armv7`
- `-sdk iphoneos`

GitHub-hosted macOS runners do not provide that old SDK/toolchain combination.

## Practical Recommendation

Use GitHub-hosted Actions for validation. For actual iPod touch 4 builds, keep a
separate legacy Mac/Xcode environment and either:

- run a self-hosted runner if the OS can support it, or
- use a modern self-hosted runner that SSHes into the legacy build machine.

The second option is often more practical because very old macOS versions may
not run the current GitHub Actions runner binary.
