# Changelog

## v0.2.0

### New

- **WiiM volume on the car screen:** - and + buttons in the Android Auto player, with the level shown briefly.
- **Sound tab in Android Auto:** volume and WiiM EQ presets, including EQ off.
- **Info tab in Android Auto:** stream format, source, Wi-Fi signal, network route, IP, EQ, output and firmware.
  It updates when the station or format changes.
- **Stream format on the now playing screen**, for example `320 kbps | 24 bit / 44.1 kHz`.
- **Phone sound controls:** volume slider, EQ, audio output (optical, line out, coaxial) and Fixed Volume.
- **Connection countdown** while the WiiM joins the hotspot, instead of a blank car screen.

### Fixed

- Steering wheel buttons now work from the start of a drive, without opening another media app first.
- Presets appear by themselves when the WiiM connects after Android Auto has started.
- Reconnecting is faster, and the app can move between WiiM devices it has used before.
- A slow WiiM reply no longer shows as "not connected" or delays button presses.
- A stalled WiiM player is now reported as "WiiM player not responding".
- The phone and car screens agree on the current station, including stations that report no name.

### Known issues

- The Android Auto queue is more stable, but can still jump back to the top after about 2 seconds during playback.
- Volume buttons have a small delay, because each press is confirmed by the WiiM.
- A few radio presets may stall over mobile data.
