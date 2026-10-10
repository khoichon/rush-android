# RUSH (Android)

A fan-made Android tribute to **Rush** from *Doors*. While it runs, the lights on your screen flicker,
a rumble builds, and Rush streaks across whatever app you're in. Tap the round button to hide in the
closet before he passes, or he catches you.

Built the same way as [noise-android](https://github.com/khoichon/noise-android): a foreground service,
overlay windows (`TYPE_APPLICATION_OVERLAY`), a Quick Settings tile and a small config screen.

## The encounter

1. **Warning** (~1.7 s): the screen flickers like a dying bulb, with the bulb sound.
2. **Approach** (4.5-9 s): the far rumble fades in, then the near one; the screen dims and starts to shake.
   Tap the hide button any time to step into the closet (two black bars close in from the sides, leaving a
   thin line of light in the middle). Tap again to step out.
3. **Pass**: Rush's smoke cloud crosses the screen. When he is at the middle, the game checks whether
   you are hiding.
4. **Safe**: the door sound plays, the lights come back and you step out.
5. **Caught** (jumpscare): black screen, static; the closed-mouth face shows for ~1 s, steps one small notch
   closer in a single tick, waits 0.7 s; then the open-mouth frame and the scream, rushing at you in jerky steps with colour-bleed glitching;
   then the death consequence (opt-in).

*Fake-outs*: a configurable share of automatic encounters flicker and play a short burst of sound, then nothing.

## Settings

Spawn automatically, min/max delay, speed (how fast Rush crosses), fake-out chance, vibration, start on boot,
what happens when he catches you (nothing / go home / lock the screen, all opt-in), and a test mode that skips
the consequence and shows a "Test the jumpscare" button. The notification's **Stop** button always works.

## Assets

`app/src/main/assets/` holds the sprites (`sprites/`) and Ogg Vorbis sounds (`sounds/`), converted from the
Doors wiki files:

| Sound | Source file |
|---|---|
| `rush_far` / `rush_near` | `PlaySound_(Rush)` / `Footsteps_(Rush)` |
| `bulb_flicker`, `bulb_break` (trimmed to 4 s) | `BulbFlicker`, `BulbBreak` |
| `fakeout` | `FakeoutRushMerged` |
| `rush_scare`, `rush_kill`, `rush_static` (trimmed to 12 s) | `RushScare`, `RushKill`, `RushJumpscareStatic` |
| `door_open` | `DoorOpen` |
| `hide_enter` / `hide_exit` | `LLocer` / `LLocer2` |

Sprites: `rush_moving` (animated, 45 frames at 20 ms), `rush_face` (jumpscare, mouth closed),
`rush_scream` (jumpscare, mouth open), `hiding` (button icon).

## Building

Open the folder in Android Studio, or let the GitHub Actions workflow build the debug APK
(`.github/workflows/android.yml`). Android 9+ (minSdk 28). On first run, allow "Display over other apps".

**Status:** the Kotlin sources compile against `android.jar` (API 35), but the project has not been built
with Gradle or run on a device yet.

## Credits

*Doors* and Rush belong to LSPLASH. Unofficial, non-commercial fan project; sprites and sounds are from the
Doors wiki. Android port by khoichon.
