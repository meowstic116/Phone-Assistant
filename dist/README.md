# Historical APKs — not for testers

Earlier personal builds were archived here as committed APKs. They are no longer kept in
the working tree: `.gitignore` already said generated distribution packages must never be
committed, and every clone was paying hundreds of megabytes for builds that were never
tester releases. Old builds remain recoverable from Git history and from the repository's
Releases page (the **Pip Dev** pre-release stream).

Build and privately sign Pip Tester using
[the distribution guide](../DISTRIBUTION.md) instead.
