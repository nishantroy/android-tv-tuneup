# Doing it with an AI coding assistant

This project started as a conversation: a coding assistant with a terminal was given the TV's
address and asked to fix it. That route suits this job, because every TV model has different
preinstalled software and an assistant can read what is on yours instead of trusting a list
written for someone else's.

You need an assistant that can run shell commands on your computer (Claude Code, or anything
similar), plus `adb` access to the TV as described in section 1 of
[manual-setup.md](manual-setup.md).

## A prompt to start from

Clone this repository, open the assistant in it, and adapt this:

> My TV is a [brand and model] running Android TV. It's slow, and [describe what bothers you:
> the home screen is full of ads, it takes ages to turn on, and so on]. I've enabled USB
> debugging and its address is [address]:5555.
>
> This repository has scripts and package lists that did this for a TCL 55S434, and a
> replacement launcher in `launcher/`. Read `README.md` and `docs/safety.md` first.
>
> Start with a read-only survey of the TV: model, Android version, memory, storage, what is
> running, and which of the packages in `debloat/` exist on it. Then show me what you propose to
> change and wait for me to agree. Don't root the TV or unlock its bootloader.

## Ground rules worth insisting on

These are what kept the original session safe. State them up front.

- **Survey first, change nothing until a plan is agreed.** The first pass should only read.
- **Remove packages one at a time,** checking after each that Android is still healthy. The
  script in `scripts/debloat.sh` does this.
- **Prefer reversible operations.** Uninstalling for the user (`pm uninstall --user 0`) and
  disabling (`pm disable-user`) can both be undone. Anything that can't be undone should be
  asked about first.
- **Keep the stock home disabled, not uninstalled,** so there is a way back from the TV's own
  Settings without a computer.
- **Write down every change with its undo command** in a file as it goes.
- **Reboot and check** before calling it done.
- **Ask it to say what it did not verify.** Things an assistant cannot test over adb include
  holding a button on the physical remote, voice search, and whether a failure that happens
  once a week is gone.

## Things only you can do

- Accept the debugging prompt on the TV.
- Press buttons on the remote when a test needs one. Waking this TV from sleep, for instance,
  could not be done over the network.
- Sign in to accounts.
- Decide what counts as bloat. Tell it which preinstalled apps you use.

## Adapting to another TV

Ask the assistant to build a package list for your model the same way these were built: list
what is running and how much memory it holds, identify each unfamiliar package, sort them into
"nothing depends on this", "some people use this", and "leave alone", and show you the list
before removing anything. If the result works well, a list for your model would make a good
contribution to `debloat/`.

The launcher's source is small and has no dependencies, so an assistant can also change how it
looks or behaves. If it does, have it check `adb shell dumpsys gfxinfo local.tvhome` afterwards:
these TVs have weak GPUs and it is easy to make the home screen slow again.
