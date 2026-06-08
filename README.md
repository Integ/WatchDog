# WatchDog

WatchDog turns an Android 8.0+ phone into a local RTSP camera intended for
Homebridge and other LAN clients. It captures camera frames, uses the device
H.264 hardware encoder, and serves the stream without a cloud dependency.

## Features

- RTSP video at `rtsp://<device-ip>:8554/video`
- 1280x720, 15fps, and 2Mbps defaults for sustained operation
- Automatic thermal throttling to 10, 5, or 2fps on Android 10+
- Hardware H.264 encoding through Android `MediaCodec`
- RTP over UDP and RTSP interleaved TCP
- Foreground camera service for streaming with the screen off
- Camera selection with a lightweight preview UI

## Long-running behavior

CameraX keeps only the newest frame, and frames above the active rate limit are
discarded before YUV conversion. A reusable encoder input buffer avoids
per-frame input allocation, while non-blocking codec submission prevents camera
backpressure from accumulating in memory.

Each RTSP/TCP client has a bounded output queue. A client that cannot consume
video quickly enough is disconnected instead of blocking the camera and
hardware encoder pipeline. The server accepts up to four concurrent clients.

The preview does not force the display to remain on. Leaving the app or turning
off the screen removes the preview while the foreground service continues to
stream.

## Homebridge

Use the URL shown in the app as the camera source:

```text
rtsp://192.168.1.50:8554/video
```

For the lowest device load, configure Homebridge to consume this stream
directly and avoid requesting an output frame rate higher than 15fps.

## Installation

1. Install the APK on an Android 8.0 or newer device.
2. Open WatchDog and grant camera permission.
3. Select the desired camera.
4. Add the displayed RTSP URL to Homebridge, VLC, or ffmpeg.

Both the Android device and the RTSP client must be on the same local network.
