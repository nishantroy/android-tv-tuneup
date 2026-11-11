# Doing it by hand

A complete walk-through, with no assistant involved. It was written from what was done to one
TCL 55S434 on Android TV 9; menu names on other models will differ.

Go in order. After each numbered section, use the TV for a minute and check it behaves before
moving on. Read [safety.md](safety.md) first.

In the commands below, replace `192.168.1.50` with your TV's address.

## 1. Set up

**On the computer**, install `adb`. On macOS with Homebrew:

```sh
brew install android-platform-tools
```

On other systems, download "SDK Platform-Tools" from Android's developer site and put it on
your `PATH`. The scripts here are bash and were run on macOS only; on Windows use WSL or Git
Bash.

**On the TV**:

1. Settings → Device Preferences → About. Scroll to **Build** and press OK seven times, until
   it says you are a developer.
2. Settings → Device Preferences → Developer options → turn on **USB debugging**. On this TV
   that also allows debugging over the network.
3. Settings → Network & Internet → select your connection and note the **IP address**.

**Connect**:

```sh
adb connect 192.168.1.50:5555
```

The TV shows "Allow USB debugging?". Tick "Always allow from this computer" and accept. Then:

```sh
adb devices          # the TV should be listed as "device", not "unauthorized"
export TV=192.168.1.50:5555
```

## 2. Look before you touch

Nothing in this section changes the TV.

```sh
adb -s $TV shell getprop ro.product.model            # what it is
adb -s $TV shell getprop ro.build.version.release    # Android version (9 or newer for the launcher)
adb -s $TV shell df -h /data                         # free storage
adb -s $TV shell cat /proc/meminfo | head -3         # memory
adb -s $TV shell dumpsys meminfo | sed -n '/Total PSS by process/,/Total PSS by OOM/p' | head -30
```

The last command lists what is running and how much memory each process holds. Processes you
don't recognise and never use are the candidates for removal.

Save the list of installed packages now. It is your reference if you later want to know what
the TV shipped with:

```sh
adb -s $TV shell pm list packages | sort > packages-before.txt
```

## 3. Remove the bloat

The lists in [`debloat/`](../debloat) are plain text, one package per line with a note saying
what it is.

- `tcl-core.txt`: telemetry, promotions, demo modes, setup leftovers. Nothing visible depends
  on these.
- `tcl-optional.txt`: things some people use (Alexa control, TCL Channel, YouTube Music, the
  TalkBack screen reader, and others). **Open this file and put a `#` in front of anything you
  want to keep.**
- `stock-home.txt`: leave this for section 6.

See what would happen, then do it:

```sh
scripts/debloat.sh $TV --dry-run debloat/tcl-core.txt debloat/tcl-optional.txt
scripts/debloat.sh $TV debloat/tcl-core.txt debloat/tcl-optional.txt
```

The script removes each package for the TV's user (`pm uninstall --user 0`), skips any that
aren't on your TV, and stops if Android's core process dies. To put things back:

```sh
scripts/debloat.sh $TV --restore debloat/tcl-core.txt debloat/tcl-optional.txt
```

Without the script, the two commands it runs per package are:

```sh
adb -s $TV shell pm uninstall --user 0 <package>           # remove
adb -s $TV shell cmd package install-existing <package>    # restore
```

**On a different brand of TV** these lists won't match. Build your own from the memory listing
in section 2 and `pm list packages -s`, look up anything you aren't sure of, and remove one
package at a time. Leave alone anything to do with settings, inputs, the remote, Bluetooth,
system UI, and Google Play services.

## 4. Fix slow power-on

Settings → Device Preferences → **Quick Start** → on.

With it off, the TV shuts down completely and every power-on is a full boot (about 35 seconds on
the test TV). With it on, the TV sleeps and wakes in a second or two. It uses a few watts more
in standby.

## 5. Shorten animations (optional)

Halves the system's window transitions:

```sh
adb -s $TV shell settings put global window_animation_scale 0.5
adb -s $TV shell settings put global transition_animation_scale 0.5
```

Undo by setting both back to `1.0`. The same two settings are in Developer options.

## 6. Replace the home screen

### Build the launcher

You need a JDK and two Android SDK packages. On macOS with Homebrew:

```sh
brew install openjdk@17
brew install --cask android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
yes | sdkmanager --licenses
sdkmanager "platforms;android-28" "build-tools;34.0.0"
```

On other systems, install a JDK and Android's "command line tools", run the same `sdkmanager`
line, and set `ANDROID_HOME` to the SDK folder. Then:

```sh
launcher/build.sh
```

That produces `launcher/build/tvhome.apk` and, the first time, a signing key at
`launcher/debug.keystore`. Keep that key: a rebuild signed with a different key will not
install over the copy already on the TV.

### Install it and make it Home

```sh
scripts/install-launcher.sh $TV
```

The script installs the app, grants it the one permission it needs to read the Watch Next list
(`READ_TV_LISTINGS`, which only adb can grant), asks before disabling the stock home, and
checks that the Home button now lands on the launcher.

The stock home is **disabled, not removed**, so that it can be switched back on from the TV
itself: Settings → Apps → See All Apps, scroll to **Disabled Apps** at the bottom → Android TV
Home → Enable.

Once you are happy with the launcher, the stock home's helper packages are no longer doing
anything:

```sh
scripts/debloat.sh $TV debloat/stock-home.txt
```

### Go back

```sh
scripts/remove-launcher.sh $TV
```

That restores the helpers, re-enables the stock home, and uninstalls the launcher. Add
`--keep-app` to leave the launcher installed with its layout saved.

## 7. If the TV is on ethernet (optional)

If the TV is wired and will stay wired, switching wifi off removes a hand-over that happened
at every boot on the test TV: it joined wifi first, used it for about a second, then dropped
it for ethernet. Settings → Network & Internet → Wi-Fi → off.

If you ever unplug the cable, you will have to switch wifi back on yourself.

## 8. Finish

Restart the TV once and check it comes up on the new home screen:

```sh
adb -s $TV reboot
```

Then decide whether to leave debugging on. It only accepts computers you have approved, but it
is a door you don't need open: Settings → Device Preferences → Developer options → USB
debugging → off. Turn it back on whenever you want to change something.

## Troubleshooting

**`adb devices` says "unauthorized".** The prompt on the TV wasn't accepted. Toggle USB
debugging off and on, reconnect, and accept it.

**The Home button still opens the old home screen.** The stock home wasn't disabled. Run
`scripts/install-launcher.sh $TV` again and answer yes. If your TV's home is not
`com.google.android.tvlauncher`, the script says so and stops; that TV needs its own approach.

**Continue Watching is empty.** Either the permission wasn't granted
(`adb -s $TV shell pm grant local.tvhome android.permission.READ_TV_LISTINGS`), or none of your
apps publish to Watch Next. If the stock home's Play Next row was empty too, it is the latter.

**The launcher misbehaves and you can't get to Settings from it.** Most remotes have a Settings
button that works regardless. From the computer, `scripts/remove-launcher.sh $TV` puts the stock
home back.

**Something stopped working after removing packages.** Restore the lists
(`scripts/debloat.sh $TV --restore ...`) and remove them again a few at a time to find which one
it was.

**A firmware update put things back.** Updates can reinstall or re-enable packages. Run the
scripts again.
