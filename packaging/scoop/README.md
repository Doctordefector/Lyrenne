# Scoop

There is deliberately no manifest in this folder.

The live Scoop manifest is in its own bucket repo, **[Doctordefector/scoop-lyrenne](https://github.com/Doctordefector/scoop-lyrenne)**, at `bucket/lyrenne.json`. An [Excavator](https://github.com/ScoopInstaller/GithubActions) workflow there rewrites the version, URL and hash on a daily cron, so the bucket edits itself after every release.

A second copy in this repo would go stale the moment Excavator ran, so it was removed. See [../README.md](../README.md) for the full picture.
