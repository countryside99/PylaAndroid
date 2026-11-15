# Known issues

Concrete, current defects and limits in this **experimental** snapshot. See [FORK_STATUS.md](FORK_STATUS.md) for the overall status. This list is not exhaustive.

## 1. Trophy counter is wrong across the 0–1000 range

- **What happens:** the trophy counter wrongly admits `1014` in a range that should stop at `1000`.
- **Observed:** a value of `14` was seen at prestige 1.
- **Impact:** trophy-based decisions can rest on a wrong reading.
- **Workaround:** none; watch the on-screen trophy value.
- **Status:** confirmed defect, unfixed in this snapshot.

## 2. Solo Showdown EXIT is unreliable

- **What happens:** the bot does not always exit Solo Showdown.
- **Impact:** the bot can be left on the result screen instead of returning to the lobby.
- **Workaround:** exit manually.
- **Status:** improved in `v4.1.0`: the visible EXIT is pressed on the elimination screen and on the ranked result screen, and both worked in the device runs listed under item 5. Other result layouts are not covered by those runs.

## 3. Movement is only partially reliable

- **What happens:** movement works some of the time but is not consistently reliable.
- **Impact:** the bot may fail to reach or follow its intended path.
- **Workaround:** none.
- **Status:** partial; under improvement.

## 4. Input stop on app closure is improved but not guaranteed

- **What happens:** closing the app stops input as expected in the improved path, but stopping is not guaranteed in every situation.
- **Impact:** input could briefly continue after the app is closed.
- **Workaround:** to stop immediately, turn off the accessibility service for PylaAndroid (see [FORK_STATUS.md](FORK_STATUS.md) → Safety).
- **Status:** improved, not guaranteed.

## 5. Device coverage is still narrow

- **Tested:** a **Samsung Galaxy S26 Ultra**, and one cloud Android 13 instance (720p, slow CPU) switched to 16:9, 20:9 and 4:3.
- **Not tested:** real tablets, foldables and any other third-party phone.
- **Requirement:** the game must be set to **English**.
- **Impact:** behaviour on other devices and game versions is unknown.
- **Status:** coverage limit. Diagnostic reports from other devices are the fastest way to widen it.

## 6. Layout scaling is measured, not guaranteed

- **What happens:** on screens narrower than 16:9 the game scales its UI with the screen width. `v4.1.0` follows that rule, which was measured on seven controls of one 4:3 frame.
- **Not covered:** the gadget and hypercharge readiness crops still use the old mapping on 4:3; centred elements are assumed to stay centred.
- **Impact:** taps or reads may be wrong on untested resolutions.
- **Status:** partly proven.

## 7. Slow devices react late

- **What happens:** on a low-end CPU one loop takes roughly 0.7–1.1 s (about 1.7 s before `v4.1.0`).
- **Impact:** movement and attacks lag behind the game, and the match state is briefly lost now and then.
- **Workaround:** none.
- **Status:** improved, target of 0.5 s not reached.

## 8. Brawler selection can time out on slow devices

- **What happens:** reading the brawler list takes longer than its time limit on a low-end CPU, and the bot stops with "Brawler selection could not be verified".
- **Workaround:** select the brawler yourself and turn automatic picking off.
- **Status:** confirmed on the slow test instance, unfixed.

## 9. Multiplying drops are handled blindly

- **What happens:** after a recognised drop or reward the bot keeps tapping through unrecognised screens for up to 45 s, so that drops which multiply are opened.
- **Not verified:** this path has not been observed on a device yet. If another unrecognised screen follows a reward, it is tapped as well during that window.
- **Status:** new in `v4.1.0`, unverified.

## Reporting a new issue

Use the **Bug report** issue form. Include the PylaAndroid version, device model, Android version, game mode and game language, reproduction steps, and expected vs actual behaviour. Screen resolution and a Pyla report ID are optional. Diagnostics details are in [DIAGNOSTICS.md](DIAGNOSTICS.md); sharing a report ID publicly may allow others to read it, so review before posting.
