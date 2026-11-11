# Instructions for AI assistants

This repository changes the software on a person's television. Read this before running
anything.

## Ask before every change

**Get explicit confirmation from the human before applying any change to a TV, however
small.** Reading is fine without asking. Changing is not.

A change is anything that alters the TV's state, including:

- removing, disabling, enabling, restoring or installing a package
- granting or revoking a permission
- writing a setting (`settings put`, `svc`, `setprop`)
- switching the Home app
- rebooting the TV or putting it to sleep
- sending key presses or taps (`input keyevent`, `input tap`): these operate whatever is on
  screen, and a key meant for one screen can toggle something on another
- running any script in `scripts/` without `--dry-run`

How to ask:

- Say exactly what you are about to do, on which TV, and how it is undone.
- Wait for a clear yes. Silence, an earlier yes to something else, or a yes to "the plan" in
  general does not cover a new change.
- Do not pass `--yes` to a script unless the human told you to for that run.
- If the human agrees to a batch (for example, one package list), that covers that batch only.

## Order of work

1. Survey the TV read-only first: model, Android version, memory, storage, what is running,
   which listed packages exist on it.
2. Show the human what you propose and why. Wait.
3. Apply one change at a time and check the TV is still healthy after each.
4. Record every change with its undo command as you go.
5. Before sending key presses, confirm which screen is showing.
6. At the end, say plainly what you did not or could not verify.

## Never

- Never root the TV, unlock its bootloader, flash firmware, or use an exploit.
- Never remove a package you cannot identify, or anything to do with settings, inputs, the
  remote, Bluetooth, system UI, or Google Play services.
- Never uninstall the stock home screen. Disable it, so it can be re-enabled from the TV.
- Never put the human's private details into this repository or any outside service: TV
  address, network names, account names, viewing history. Use the launcher's demo mode for
  screenshots.

## About this repository

It is a demonstration, shared as-is for learning. It was tested on one TV model. The package
lists in `debloat/` will not match other brands, and following them blindly on another TV can
break it. See `docs/safety.md`.
