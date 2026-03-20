# WatchDog

WatchDog turns an Android phone into a high-performance local network security camera. The app utilizes the device's camera to capture real-time video, encodes it into H.264, and streams it over the Local Area Network using the RTSP protocol. 

## Features

- **Direct RTSP Streaming:** Streams live video on the standard RTSP port `8554`.
- **Low Latency:** Achieved through direct hardware encoding without additional intermediary networking overhead.
- **Hardware Acceleration:** Uses Android's `MediaCodec` to efficiently compress video frames to H.264.
- **Native Resolution:** Captures and encodes in consistent landscape `1280x720` HD resolution, ensuring clear details.

## Implementation Details

The core functionality of WatchDog comprises three primary components:
1. **Camera Acquisition:** Uses the Android `CameraX` library to configure a landscape orientation layout. An `ImageAnalysis` analyzer is utilized to siphon raw YUV_420_888 frames at 30 FPS.
2. **Video Encoding:** A custom `yuv420ToNv12` converter processes the camera frames, and feeds them into the system's `MediaCodec` initialized in `ByteBuffer` input mode. The encoder strips the frame configuration and packages them into NAL units containing SPS and PPS packets.
3. **RTSP Server:** A lightweight, pure-Kotlin RTSP (RFC 2326) Server implementation handles negotiation protocols (OPTIONS, DESCRIBE, SETUP, PLAY). It packetizes the encoded NAL units using FU-A fragmentation over RTP/UDP (or TCP interleaved) for video consumption.

## Installation

You can download the latest compiled `.apk` directly from the **[GitHub Releases](../../releases)** page.

1. Download the `watchdog.apk` file on your Android device.
2. Ensure you have allowed "Install from Unknown Sources" in your Android settings.
3. Install and open the app. Accept the required camera permissions.
4. Note the RTSP address displayed on the screen (e.g., `rtsp://<device_ip>:8554/video`).
5. Open the stream using a media player (e.g., VLC, QuickTime, or ffmpeg) connected to the same local network.
