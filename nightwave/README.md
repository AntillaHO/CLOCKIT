# NIGHTWAVE

A native Android 10+ sleep-audio monitor. Built with Java and Android Gradle Plugin 8.7.3.

- Live digital RMS and peak level monitoring of apps allowing Android AudioPlaybackCapture.
- Adjustable peak warning threshold in dBFS.
- Optional OS media volume ceiling from 5% to 100%, enforced while NIGHTWAVE is running (not a DSP limiter).
- No external servers, no internet permission, no audio data storage.

**Important:** YouTube and other apps may block playback capture. Background monitoring cannot be guaranteed; Android may stop Activity-based capture. The media-volume ceiling limits total output volume but is not a per-sample audio limiter and does not guarantee protection against sudden loud sounds. Requires explicit projection consent. dBFS measures digital amplitude rather than acoustic dB SPL.

Build: `gradle :app:assembleDebug --no-daemon` with Java 17 and Android SDK 35.
