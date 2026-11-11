# Safety, risks and what was not verified

Read this before changing your TV. It is an honest account of what the steps in this repository
do, what can go wrong, and what was never tested. It is not legal advice.

**You use this entirely at your own risk.** It is a demonstration shared for learning, tested
on one TV, with no warranty and no support. Whatever happens to your device, data, accounts or
warranty as a result of following it is your responsibility alone.

## What this does not do

- It does not root the TV, unlock its bootloader, flash firmware, or use any exploit. The TV
  it was developed on cannot be rooted by any means the authors know of.
- It does not modify or patch any app, bypass any login, subscription or copy protection, or
  block ads inside apps.
- It does not touch the read-only system partition. Removed packages remain there and can be
  reinstated.

Everything goes through `adb`, the debugging interface Android provides, using commands
Android documents: `pm uninstall --user 0`, `pm disable-user`, `pm grant`, `settings put`, and
installing an app.

## What it changes, and how each is undone

| Change | Undo |
| --- | --- |
| Packages uninstalled for the TV's user | `scripts/debloat.sh <tv> --restore <lists>`, or a factory reset |
| Stock home screen disabled | `scripts/remove-launcher.sh <tv>`, or on the TV: Settings → Apps → See All Apps → Disabled Apps → Android TV Home → Enable |
| Launcher installed and set as Home | `scripts/remove-launcher.sh <tv>` |
| Quick Start, animation scales, wifi | The TV's own Settings |
| Now Playing listener enabled (optional) | `adb shell cmd notification disallow_listener local.tvhome/local.tvhome.MediaListener`, or uninstalling the launcher |

The remove-and-restore path and the switch to the launcher and back were each run on the test
TV and worked. A factory reset returns the TV to how it shipped regardless.

## Real risks

| Risk | How it shows up | What limits it |
| --- | --- | --- |
| Removing a package your model needs | A feature stops working, or in the worst case the TV won't finish starting | The lists were tested on one model only. On others, remove one package at a time and test. The script stops if Android's core process dies. Restore puts packages back, but needs the TV to start far enough for adb to connect; if it doesn't, the fallback is a factory reset from the TV's recovery menu, which erases your apps and sign-ins |
| No working home screen | Pressing Home shows nothing useful | The stock home is only disabled, and can be re-enabled from Settings on the TV or with one script |
| Debugging left switched on | Another device on your network could ask to debug the TV | The TV refuses computers you haven't approved on its screen. Switch USB debugging off when finished |
| Removing accessibility software | TalkBack is in the optional list | It is marked in the list. Keep it if anyone in the household uses a screen reader |
| Higher standby power | Quick Start keeps the TV in sleep rather than off | A few watts. Leave it off if you prefer, at the cost of the slow boot |
| Wifi switched off | If the ethernet cable is removed, the TV has no network | Only do this on a TV that stays wired |
| Warranty or support | A manufacturer could decline to help with a TV whose software you changed | Nothing here is permanent; undo it, or factory reset, before asking for support. Check your own warranty terms |
| Firmware updates | An update may reinstall or re-enable what you removed, or change how the home screen is chosen | Run the scripts again. Updates were not tested |

## Privacy and network behaviour of the launcher

- It keeps one small settings file on the TV: the order of your apps, which you removed from
  Home, and when you last opened each one from the launcher (for the carousel's order).
- With the `READ_TV_LISTINGS` permission it reads the TV's Watch Next list, which is what the
  stock home's Play Next row showed. That list never leaves the TV.
- Its only network use is downloading the artwork for Continue Watching, from the addresses
  the streaming apps themselves put in that list. Those servers see a request for an image,
  as they did when the stock home showed the same row.
- Now Playing is off unless you enable it. Enabling it makes the launcher a notification
  listener, which is the only way Android lets an app see what is playing. That permission
  would also let an app read every notification on the TV. The launcher's listener is an
  empty class (`MediaListener.java`, a few lines, worth reading) and looks at none of them; it
  uses the access only to read the title, artist and artwork of the current media session
  and to send it play and pause. Artwork given as a web address is downloaded from that
  address.
- It has no analytics, no account, no ads, and contacts no server belonging to this project.

## Legal notes

Not legal advice, and the position can differ by country.

- You are changing settings and software on a device you own, through an interface its maker
  provides, without defeating any protection. That is ordinary use.
- The launcher is original code under the MIT License. The Inter typeface is redistributed
  under the SIL Open Font License, with its license file included.
- No third-party logos or artwork are in this repository's code. App logos appear in the
  screenshots because that is what the launcher displays on a TV with those apps installed.
  The "before" screenshot is of Google's Android TV home; the promotional images it was
  showing are pixelated.
- The carousel is a generic tilted-card layout. It does not use any other product's name,
  artwork or code.

## What was verified, and what was not

Verified on the test TV:

- The TV reboots cleanly into the launcher after every package in the three lists is removed.
- Removing and restoring packages with the script, and switching Home to the launcher and back.
- Opening apps from the carousel and the grid, the Inputs and Settings buttons, resuming a
  Continue Watching entry, adding, moving and removing apps.
- One sleep and wake cycle with Quick Start on: about 1.4 seconds to wake, network back
  within about five seconds.
- Frame times while the carousel slides: a 12 to 13 ms median, measured with `dumpsys gfxinfo`.
- The carousel reordering after an app is opened, with the grid keeping its order.
- Now Playing with one app, Spotify: the card appeared with artwork, title and artist, and
  OK paused and resumed playback.

Not verified:

- **Any other TV model, brand or Android version.**
- Holding OK on a physical remote to open an app's options. It relies on Android's standard
  long-press and was only exercised through the Menu key.
- Holding OK on the Now Playing card to open the app that is playing, for the same reason.
- Now Playing with any app other than Spotify.
- The grid tile that switches the carousel between recent-first and grid order (it appears;
  pressing it was not exercised).
- Voice search and Google Assistant after the removals.
- Casting to the TV after the removals.
- Switching to an HDMI input from the Inputs menu (the menu opens; no input was selected).
- Behaviour after a firmware update.
- Whether the intermittent network failures that prompted the project are gone. They did not
  recur during testing, which proves little about something intermittent.
