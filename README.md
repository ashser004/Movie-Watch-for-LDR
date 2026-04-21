# KanDeloo

Watch videos together with the people you care about — even when you're miles apart.

KanDeloo lets long-distance couples and friends watch videos in sync, chat, and react in real time.
The app focuses on **low latency**, **minimal data usage**, and **simple local video playback**.

---

## Features

* Synchronized video playback between users
* Watch local videos together in real time
* Live chat and **Voice Notes** during playback
* Emoji reactions while watching
* Synchronized video controls including **Play, Pause, and Skip (+/- 10s)**
* **Built-in Auto-Update** to keep the app up to date automatically
* Very low latency synchronization
* Minimal internet data usage
* Works with locally stored videos

---

## How It Works

KanDeloo does **not stream the video over the internet**.

Instead:

1. Both users load the **same video file locally**.
2. The app synchronizes **play, pause, and seek actions**.
3. Playback stays synchronized using realtime updates.

This means:

* No heavy video streaming
* Very low data usage
* Faster and smoother sync

---

## Installation

### Option 1 – Install APK (Recommended)

1. Download the latest APK from the **Releases** section of this repository.
2. Transfer the APK to your Android phone.
3. Enable **Install from Unknown Sources** if prompted.
4. Install the APK.

---

### Option 2 – Build From Source

Requirements:

* Android Studio
* JDK 17+
* Android SDK

Steps:

```bash
git clone https://github.com/ashser004/Movie-Watch-for-LDR.git
cd Movie-Watch-for-LDR
```

Open the project in **Android Studio**, then build:

```
Build → Generate Signed APK
```

---

## Usage Guide

### 1. Sign In

Open the app and sign in using your Google account.

---

### 2. Create a Room

Tap **Create Room**.
The app will generate a **room code**.

Share this code with your friend or partner.

---

### 3. Join a Room

Tap **Join Room** and enter the room code.

---

### 4. Load the Video

Both users must:

1. Select the **same video file** from their device.
2. Wait for the other person to load the video.

---

### 5. Start Watching

Once both videos are loaded:

* Press **Play**
* The playback will sync automatically.

You can:

* Pause
* Seek
* React
* Chat

All actions stay synchronized.

---

## Data Usage

KanDeloo only syncs **playback events and messages**.

The video file itself **is never uploaded or streamed**, which keeps data usage extremely low.

---

## Device Requirements & Media Support

KanDeloo relies on your phone's native hardware decoders to play videos efficiently without draining your battery. 
* **Supported Devices:** We focus on delivering a great experience for modern smartphones running **Android 12 and above**.
* **Note on High-Quality Formats (HEVC/H.265):** Complex, high-bitdepth video files like **10-bit HEVC (Main 10)** require specialized hardware decoding chips. While KanDeloo is built to smoothly play any file your phone supports, some budget or older Android devices physically lack the processor necessary to decode these high-end, cinema-grade files. If you encounter a "Codec not supported" issue, it means the specific video file exceeds the hardware playback limits of your device.

---

## Future Roadmap

KanDeloo is an evolving project, and several improvements are planned for upcoming versions.

### Planned Features

* **Improved synchronization algorithms**
  Further reduction of playback latency.

* **Better reaction system**
  More interactive emoji reactions and animations.

* **Watch history and room management**

* **Cross-platform support**
  Possible support for web or iOS versions in the future.

---

## Contributing

Contributions are welcome.

You can:

* Report bugs
* Suggest improvements
* Submit pull requests

Please ensure your changes follow the project structure.

---

## License

KanDeloo is licensed under the **GNU General Public License v3.0**.

See the LICENSE file for full terms.

© 2026 Ashmith
