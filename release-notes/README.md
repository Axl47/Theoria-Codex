# Curated Release Notes

Each intentional app release adds one Markdown file named `v<major>.<minor>.<patch>.md`.
The file is the exact GitHub prerelease body and the text shown by the in-app changelog.

Write for someone using the app, not for someone reading the source. Group the meaningful changes under short `##` headings such as `Highlights`, `New`, `Improvements`, `Fixes`, and `Known Issues`. Omit internal refactors, test-only work, and implementation detail unless users will notice its effect.

The release workflow requires the file for the matching annotated Git tag. For example, the `v0.5.4` release needs `release-notes/v0.5.4.md` in the tagged commit.
