# WatchDog Viewer for iOS

This is a small Objective-C iOS app intended for iPod touch 4th generation
(iOS 6.x). It opens a WatchDog RTSP URL such as:

```text
rtsp://192.168.1.50:8554/video
```

The app intentionally avoids Swift, Storyboards, Auto Layout, and modern iOS
APIs so it can be opened by an older Xcode toolchain that can still deploy to
iOS 6 devices.

## Playback Backend

The first implementation uses `MPMoviePlayerController`, the lightest available
system playback path for iOS 6-era devices. This keeps the app small and lets
the device use any hardware decoding path available to the OS.

Important: RTSP support on iOS depends on the system media stack. If an iPod
touch 4 cannot play WatchDog's RTSP stream through `MPMoviePlayerController`,
the next step is to replace the playback backend with an embedded decoder stack
such as FFmpeg plus an OpenGL ES YUV renderer. That route is heavier but gives
full control over RTSP/RTP/H.264 handling.

## Recommended WatchDog Settings For iPod touch 4

Start with a low-load Android stream profile when testing old iOS hardware:

- 640x480 or 640x360
- 10 to 15 fps
- 500 to 900 kbps
- H.264 Baseline profile if possible
- RTSP interleaved TCP enabled on the server

The current Android app defaults to 1280x720, 15 fps, 2 Mbps. That may be too
heavy for stable playback on iPod touch 4 over older Wi-Fi.

## Build

Open:

```text
ios/WatchDogViewer/WatchDogViewer.xcodeproj
```

Use an old Xcode version that can still build and deploy to iOS 6 devices. The
project deployment target is iOS 6.0 and the intended device architecture is
armv7.

This repository environment only has Command Line Tools, not full Xcode, so the
project has not been compiled locally here.
