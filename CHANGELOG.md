# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
where practical.

## [Unreleased]

### Project documentation

- Documented canonical Git repository, SourceForge as historical home, and
  license lineage (GPL-3.0 from the original 2010 open-source release).
- Added `AUTHORS`, `THANKS`, `LICENSING.md`, `RELEASING.md`, and `LICENSE`
  (pointer plus copyright notice; full text remains in `COPYING`).
- Expanded `RELEASING.md` with steps for an official SourceForge **Git mirror**
  and for mirroring **Files** downloads; README points maintainers to it.
- Added `scripts/push-sourceforge-mirror.ps1` to push the **current branch** (or `-Branch …`) and tags to
  `ssh://fla1257@git.code.sf.net/p/jpdfbookmarks/githublink` (`-Branch main` for releases after merge).

## Historical lineage (not a single release)

This section summarizes how today’s tree relates to earlier homes of the code.

1. **SourceForge (Subversion)** — original public development and downloads:
 https://sourceforge.net/projects/jpdfbookmarks/
2. **`branches/pdfbox` on SourceForge** — Apache PDFBox migration work; the
   Maven line in README was bootstrapped from artifacts such as
   `jpdfbookmarks-code-r213-branches-pdfbox`.
3. **life888888 / JPdfBookmarks on GitHub** — Maven build, JDK 21, CJK GUI
   fixes, installers build companion repo.
4. **fpetrocchi / JPdfBookmarks** — canonical Git repository for ongoing work
   continuing the same program under the same license.

For per-version feature lists prior to this changelog, see `README.adoc` and
past release notes on SourceForge/GitHub.
