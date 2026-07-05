# WatchDog iOS Port for iPod touch 4th Generation

This document describes what is required to port the Android WatchDog app to
iOS while targeting iPod touch 4th generation.

## Target Device Constraints

iPod touch 4th generation is a legacy device:

- Maximum official OS: iOS 6.1.6
- CPU/RAM class: Apple A4, 256 MB RAM
- Screen: 960x640, 3.5 inch
- Rear camera video: 720p class
- Development stack: Objective-C, UIKit, AVFoundation, BSD sockets

Modern Swift, SwiftUI, Network.framework, and VideoToolbox-based sample code are
not a practical fit for this target.

## Android Behavior To Match

The Android app currently provides:

- Fullscreen camera preview in landscape.
- Camera selection between available cameras.
- Local RTSP URL shown as `rtsp://<device-ip>:8554/video`.
- H.264 encoding at a sustained target of 1280x720, 15 fps, 2 Mbps.
- RTSP server with OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER.
- RTP packetization for H.264, including FU-A fragmentation.
- RTP over UDP and RTSP interleaved TCP.
- Up to four concurrent clients.
- Foreground/background streaming behavior with screen-off support on Android.
- Thermal throttling on newer Android versions.

## Important iOS 6 Limitations

The Android app cannot be made functionally identical on iPod touch 4 without
changing product requirements.

### Background Camera Streaming

iOS 6 does not allow a normal App Store-style app to keep capturing camera video
after it is sent to the background or when the device is locked. The Android
foreground service behavior has no equivalent on iOS 6.

Closest behavior:

- Keep streaming while the app is foregrounded.
- Disable idle sleep while streaming with `UIApplication.idleTimerDisabled`.
- Stop streaming when the app resigns active or enters background.

### Live H.264 Elementary Stream

The Android app uses `MediaCodec` to get H.264 NAL units directly. On iOS 6,
there is no clean public equivalent for live H.264 elementary-stream output from
the camera suitable for RTSP packetization.

Possible approaches:

- Use a bundled software encoder such as x264.
- Lower the default target to improve thermal and memory behavior.
- Consider 640x480 or 640x360 at 10 to 15 fps for iPod touch 4.

This adds licensing, size, build, and performance considerations.

### Thermal Monitoring

The Android thermal callback has no direct iOS 6 equivalent. The iOS app should
use conservative defaults and manual frame dropping instead.

## Recommended iOS Feature Set

For an iPod touch 4 compatible build, target this behavior:

- Landscape-only UIKit app.
- `AVCaptureSession` preview.
- Rear/front camera selector.
- Local IP detection.
- RTSP server on port 8554.
- H.264/RTP server compatible with VLC, ffmpeg, Homebridge, and similar LAN
  clients.
- Foreground-only streaming.
- Idle timer disabled while streaming.
- Conservative default stream profile:
  - Rear camera: 640x480 or 640x360, 10 to 15 fps, 500 to 900 kbps.
  - Front camera: VGA or lower, 10 fps.

If exact 720p/15fps/2Mbps is mandatory, it must be validated on real hardware
early because the A4/256 MB device class is likely to overheat, drop frames, or
run out of memory when software encoding and serving multiple clients.

## Suggested Project Structure

Create a separate iOS project rather than mixing build systems:

```text
ios/WatchDog/
  WatchDog.xcodeproj/
  WatchDog/
    main.m
    AppDelegate.h
    AppDelegate.m
    WDViewController.h
    WDViewController.m
    WDCameraController.h
    WDCameraController.m
    WDRTSPServer.h
    WDRTSPServer.m
    WDRTPH264Packetizer.h
    WDRTPH264Packetizer.m
    WDH264Encoder.h
    WDH264Encoder.m
    WDNetworkInfo.h
    WDNetworkInfo.m
    Info.plist
```

## Implementation Plan

1. Build the UIKit shell.
   - Landscape-only root view controller.
   - Fullscreen `AVCaptureVideoPreviewLayer`.
   - Bottom translucent overlay matching the Android UI.
   - Camera picker and RTSP URL label.

2. Implement camera capture.
   - Use `AVCaptureSession`.
   - Use `AVCaptureVideoDataOutput`.
   - Drop frames above the active target fps.
   - Support rear/front camera switching.

3. Implement RTSP/RTP.
   - Port the current Kotlin `RtspServer` logic to Objective-C.
   - Keep OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER.
   - Keep UDP and interleaved TCP support if performance allows.
   - Keep max client count low on iPod touch 4, ideally one or two clients.

4. Add H.264 encoding.
   - Preferred for iOS 6: software encoder integration.
   - Output Annex B NAL units or convert length-prefixed NAL units before RTP.
   - Cache SPS/PPS for SDP and IDR pre-roll.

5. Validate on real iPod touch 4 hardware.
   - Test VLC/ffmpeg/Homebridge.
   - Test front/rear switching.
   - Test 30 minute foreground streaming.
   - Tune resolution, fps, and bitrate.

## Product Decision Needed

Choose one of these targets before implementation starts:

1. Compatibility-first:
   Foreground-only, software H.264, lower resolution, iPod touch 4 supported.

2. Functionality-first:
   Match Android behavior more closely, but require newer iOS hardware and an
   iOS version with modern media APIs.

3. Jailbreak/private-API route:
   Potentially closer background behavior, but not suitable for normal
   distribution and much harder to maintain.

For this repository, the compatibility-first route is the realistic choice if
iPod touch 4th generation support is non-negotiable.
