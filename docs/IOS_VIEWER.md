# WatchDog iOS Viewer

The current iOS direction is viewer-only: the Android WatchDog app remains the
camera/RTSP server, and the iOS app plays the stream.

## Target

- Device: iPod touch 4th generation
- OS: iOS 6.x
- App language: Objective-C
- UI framework: UIKit
- First playback backend: `MPMoviePlayerController`

## Why Viewer-Only Is Better

Running the camera and RTSP server on iPod touch 4 is constrained by iOS 6
background and media APIs. Viewing a LAN stream is much more realistic because
the iPod only needs to receive and decode H.264 video.

## Current Implementation

The initial project is at:

```text
ios/WatchDogViewer/WatchDogViewer.xcodeproj
```

It provides:

- Landscape-only iPhone/iPod app.
- RTSP URL input.
- Connect and Stop controls.
- Fullscreen black video area.
- Last URL persistence through `NSUserDefaults`.
- Idle timer disabled while playback is active.

## Compatibility Risk

iOS 6 RTSP behavior depends on the system media player. If the system player
does not accept the WatchDog stream, the app will need a custom playback backend:

1. RTSP client.
2. RTP H.264 depacketizer.
3. H.264 decoder.
4. OpenGL ES YUV renderer.

For iPod touch 4, FFmpeg software decoding may require lowering the Android
stream to 640x360 or 640x480 at 10 to 15 fps.
