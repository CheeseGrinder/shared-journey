# Security Policy

Shared Journey is a hobby project (see the [support policy](README.md) in the README): no
SLA, no guaranteed fix timeline. That said, security reports are taken seriously and
handled with priority over regular bug reports, since they can affect a whole server and
its players.

## Reporting a vulnerability

**Do not open a public issue for a security vulnerability.** Use GitHub's private
vulnerability reporting instead:

1. Go to the repository's **Security** tab.
2. Click **"Report a vulnerability"**.
3. Describe the issue: affected version, reproduction steps, and impact (what an attacker
   can do — crash the server, read/write data outside the intended scope, execute code,
   etc.).

This opens a private draft advisory visible only to the maintainer and you, so the issue
isn't public before a fix ships.

## Scope

In scope: anything in this repository's own code — the network protocol (packet
handling, delta sync), the region/disk storage layer (path handling, deserialization),
waypoint and command permission checks, and the JourneyMap reflection bridge.

Out of scope: vulnerabilities in Minecraft, NeoForge, or third-party mods this project
integrates with (report those upstream) — unless the report is specifically about how
Shared Journey misuses them.

## What happens next

The report will be acknowledged when read (no fixed deadline — see the support policy).
If confirmed, a fix targets the next release; you'll be credited in the advisory unless
you ask not to be.
