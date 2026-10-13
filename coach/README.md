# Coach workspace — one-time setup

`coach/` in this repo is the **template** for the coach workspace (DESIGN.md §7.1). The coach
is the nabu agent that writes weekly outlines and session Flight Plans, and it runs in a
workspace on the PC — not in this repository.

Copy this folder out **once**, to its own path, e.g. `C:/Users/corpo/liftoff-coach`. The copy is
its **own git repository and never part of this one**: the coach is allowed to write in its own
workspace, so it must not be able to reach the app's source tree. Do not symlink it, do not add
it as a submodule, and do not check the copy back in here. Later changes to this template reach
the copy only by re-copying it by hand.

## 1. Copy the template out and start its repo

Copy the whole `coach/` folder to the workspace path, then start a repository there:

```sh
cp -r coach /c/Users/corpo/liftoff-coach
cd /c/Users/corpo/liftoff-coach && git init
```

The same two steps in PowerShell:

```powershell
Copy-Item -Recurse coach C:\Users\corpo\liftoff-coach
cd C:\Users\corpo\liftoff-coach; git init
```

`COACH.md` is the coach's standing instructions (§7.1.1). It is read from the workspace, so it
has to be in the copy — the prompt only tells the coach to follow it.

## 2. Turn off the verify gate for that path

In `~/.nabu/config.json`, add the workspace to the verify module's per-workspace commands with
an empty command:

```json
"modules": {"verify": {"commands": {"<workspace path>": ""}}}
```

On this PC that file is `C:/Users/corpo/.nabu/config.json` (the daemon keeps it beside its
sessions and log, in the daemon root). The nabu harness was renamed rho, so on this machine the
daemon root is `C:/Users/corpo/.rho` and the file is `C:/Users/corpo/.rho/config.json` — use
whichever the daemon actually reads.

Fill in the path you chose in step 1:

```json
"modules": {"verify": {"commands": {"C:/Users/corpo/liftoff-coach": ""}}}
```

An entry for a workspace **replaces** the global `modules.verify.command` there, so the empty
string is what switches the gate off. Without it, a global verify command such as
`go build ./...` would run whenever the coach touched a file — in a folder that holds two
Markdown files, that gate can only fail, and it would veto the coach's own turn.

## 3. Write down what Mission Control needs

Make sure the daemon is running and reachable from the phone over Tailscale, then note four
things. Mission Control has a field for each:

| Mission Control field | Where it comes from |
|---|---|
| **Daemon host** | the PC's Tailscale address, the address the daemon is bound to |
| **Port** | the daemon's port, default **8737** |
| **Token** | the daemon's bearer token, the same one nabu's own clients use (in the daemon config, `daemon.token`) — copy it from there, don't commit it here |
| **Coach workspace path** | the path from step 1, e.g. `C:/Users/corpo/liftoff-coach` |

The workspace path is not decoration: generation sends it as the nabu session's `workspace`
(§7.3), so a generation started with that field empty cannot run.

If the phone cannot reach the daemon — PC off, Tailscale down, daemon not started — the app
retries with backoff and the Launchpad says **"Mission Control offline"**.
