# Release process

This document describes how to publish a release that stays traceable to the
historical JPdfBookmarks project (SourceForge) while using modern Git workflows.

## Versioning

1. Choose the next version (e.g. continue from `3.0.2` → `3.1.0` or `3.0.3`
   per SemVer and user-visible changes).
2. Update the Maven `<version>` in the root [`pom.xml`](pom.xml) and any
   module POMs that inherit or override it.
3. Update [`CHANGELOG.md`](CHANGELOG.md) with a dated section for the release.

## Cloud AI bundled defaults (Maven)

End-user builds that should use **cloud index extraction** without manual setup rely on
[`jpdfbookmarks_core/src/main/resources/it/flavianopetrocchi/jpdfbookmarks/ai-cloud-defaults.properties`](jpdfbookmarks_core/src/main/resources/it/flavianopetrocchi/jpdfbookmarks/ai-cloud-defaults.properties).
That file uses Maven placeholders (`${ai.cloud.*}`). In the **committed** root [`pom.xml`](pom.xml) those properties are intentionally **empty** so the public tree
carries no deployment-specific values. They must be **set when you package** the
artifacts you ship (CI secrets, a private `settings.xml` profile, or a one-off
`mvn` command). Do **not** commit real secrets (e.g. Supabase **service_role**,
Stripe secret keys, OpenAI keys): only **public client** values belong here, such as
the Supabase **anon** key and HTTPS URLs to your Edge Functions / checkout links.

Relevant POM property names (all optional for a given release, but **cloud mode
needs at least** `process.index` URL and `anon` key to be non-empty in the JAR if
users have no other way to configure them):

| Property | Purpose |
|----------|---------|
| `ai.cloud.process.index.url` | `POST` URL for the process-index Edge Function |
| `ai.cloud.anon.key` | Supabase **anon** JWT (client key) |
| `ai.cloud.check.payment.url` | Payment / entitlement check (if used) |
| `ai.cloud.fetch.full.url` | Fetch full bookmarks after payment (if used) |
| `ai.cloud.stripe.checkout.mini.url` | Stripe Checkout URL for mini tier (if used) |
| `ai.cloud.stripe.checkout.advanced.url` | Stripe Checkout URL for advanced tier (if used) |

Example (replace placeholders; line breaks for readability only):

```text
mvn -q clean package ^
  -Dai.cloud.process.index.url="https://YOUR_REF.supabase.co/functions/v1/process-index" ^
  -Dai.cloud.anon.key="YOUR_SUPABASE_ANON_JWT" ^
  -Dai.cloud.check.payment.url="https://YOUR_REF.supabase.co/functions/v1/check-payment" ^
  -Dai.cloud.fetch.full.url="https://YOUR_REF.supabase.co/functions/v1/fetch-bookmarks" ^
  -Dai.cloud.stripe.checkout.mini.url="https://YOUR_CHECKOUT_MINI_URL" ^
  -Dai.cloud.stripe.checkout.advanced.url="https://YOUR_CHECKOUT_ADVANCED_URL"
```

On Unix shells, use `\` instead of `^` for line continuation, or pass the same
`-D` flags on one line.

On Windows, from the repo root you can use [`scripts/package-with-local-cloud.ps1`](scripts/package-with-local-cloud.ps1):
it reads **`local-ai-cloud-maven.properties`** (gitignored) and runs Maven with
one `-D` per `ai.cloud.*` line. Examples:

```text
.\scripts\package-with-local-cloud.ps1
.\scripts\package-with-local-cloud.ps1 -MavenGoals package
.\scripts\package-with-local-cloud.ps1 -DryRun
.\scripts\package-with-local-cloud.ps1 -PropertiesFile C:\secure\my-cloud.properties
```

Use `-SkipTests` only when appropriate (adds `-DskipTests`).

**Verify the packaged core JAR** (path depends on module output; adjust if your
assembly differs):

1. Confirm the filtered resource has **no** literal `${...}` left and that URL /
   anon fields are non-empty when you intend cloud to work out of the box, e.g.  
   `jar xf jpdfbookmarks_core/target/jpdfbookmarks_core-*.jar it/flavianopetrocchi/jpdfbookmarks/ai-cloud-defaults.properties`  
   then open that file, or  
   `jar xf ... && type it\flavianopetrocchi\jpdfbookmarks\ai-cloud-defaults.properties` (Windows).
2. A **developer** build with empty properties is still valid: cloud defaults in
   the JAR will be empty; local OpenAI/Ollama or manual prefs / dev flags apply
   instead (see `OptionsDlg.isAiOptionsTabVisible()` and project docs).

## Git tag

1. Ensure `main` (or your release branch) builds cleanly:
   `mvn -q clean verify`
2. Create a signed or annotated tag (example):
   `git tag -s v3.1.0 -m "JPdfBookmarks 3.1.0"`
3. Push commits and tags:
   `git push origin main --tags`

## GitHub Releases

1. On GitHub, open **Releases** → **Draft a new release** from the tag.
2. Attach the same artifacts users expect (e.g. `jpdfbookmarks-*.zip`,
   `jpdfbookmarks-*.tar.gz`, source archives from `mvn package` / assembly).
3. Paste the section from `CHANGELOG.md` into the release description.
4. Publish **checksums** (SHA-256) for each file in the release notes.

## SourceForge Git mirror (official)

Goal: a **read-only duplicate** of the canonical Git history on SourceForge so
users who still use the project page can **clone/browse** the same tree as on
GitHub. Day-to-day development and pull requests stay on GitHub.

Official SourceForge Git documentation:
https://sourceforge.net/p/forge/documentation/Git/

### One-time: enable Git and choose a repository path

1. Log in as project admin on
   [JPdfBookmarks on SourceForge](https://sourceforge.net/projects/jpdfbookmarks/).
2. Open **Admin** → **Tools** → enable **Git**.
3. Pick a **label** for the repository (this becomes the last segment of the
   URL). Examples: `git`, `code`, or `jpdfbookmarks`. Remember it as   `REPOSITORY` below.

After creation, SourceForge shows clone URLs. The read/write form is:

`ssh://YOUR_SF_USERNAME@git.code.sf.net/p/jpdfbookmarks/REPOSITORY`

Read-only HTTPS clone (for end users) typically looks like:

`https://git.code.sf.net/p/jpdfbookmarks/REPOSITORY`

### One-time: add remote and push from your existing clone

From your local clone of the **canonical** repo (with full history and tags),
add a second remote (do **not** replace `origin`, which should remain GitHub):

```text
git remote add sourceforge ssh://YOUR_SF_USERNAME@git.code.sf.net/p/jpdfbookmarks/REPOSITORY
```

Push the branch you want mirrored (active development or `main` after a release merge):

```text
git push sourceforge feature/thumbnails-save-ai-wip
git push sourceforge main
```

Push **all tags** (releases):

```text
git push sourceforge --tags
```

Optional — push every branch once:

```text
git push sourceforge --all
```

If the empty remote still expects `master`, either set the default branch in
SourceForge (see their docs under “Changing Default Branch”) or map once, e.g. `git push sourceforge feature/thumbnails-save-ai-wip:master` or `git push sourceforge main:master`.

### Ongoing: keep the mirror updated

Whenever you update the branch you want visible on SourceForge, refresh the mirror:

```text
git fetch origin
git push sourceforge feature/thumbnails-save-ai-wip
git push sourceforge --tags
```

Use your normal GitHub workflow; this is an extra `git push` to `sourceforge`.
After merging a release to `main`, also run `git push sourceforge main` (or use `-Branch main` with the script below) so the mirror tracks the release line.

On Windows, from the repo root you can run
[`scripts/push-sourceforge-mirror.ps1`](scripts/push-sourceforge-mirror.ps1):
it configures the remote `sourceforge` as
`ssh://fla1257@git.code.sf.net/p/jpdfbookmarks/githublink` (SourceForge label **jpdfbookmarks-githublink**), then pushes **the current checked-out branch** (no `-Branch`) and `--tags`. Use `-Branch main` when mirroring `main`, or `-Branch feature/thumbnails-save-ai-wip` if you are not on that branch.

### Project page text (recommended)

In **Summary** or **External Links**, add:

- **Canonical development & issues:** https://github.com/fpetrocchi/JPdfBookmarks
- **Git mirror (browse/clone):** the HTTPS URL SourceForge shows for your Git repo
- **Subversion:** legacy only (optional note), if you still expose the old SVN tree

## SourceForge Files (download mirror)

1. Log in to [the SourceForge project](https://sourceforge.net/projects/jpdfbookmarks/).
2. Open **Files** and create or reuse a folder for the version (e.g. `3.1.0`).
3. Upload the **same** binaries and source archives as on GitHub (zip/tar.gz,
   and optional installers). Large uploads use the SF web UI or `scp`/rsync as
   documented on SourceForge.
4. Paste the same **release notes** snippet you used on GitHub; add **SHA-256**
   for each file (copy from GitHub release or `certutil` / `sha256sum`).
5. In **Summary** or **External Links**, keep the link to the **canonical Git
   repository**: https://github.com/fpetrocchi/JPdfBookmarks and the **Git
   mirror** URL from the section above.

## Consistency checklist

- [ ] Version in POM matches tag and filenames.
- [ ] `CHANGELOG.md` and GitHub release text match.
- [ ] **Cloud distribution:** release binaries were built with the intended `ai.cloud.*` Maven properties (if users rely on bundled cloud defaults);
      spot-check `ai-cloud-defaults.properties` inside the shipped JAR.
- [ ] `COPYING` (and `LICENSE` pointer) included in source archives.
- [ ] `AUTHORS` / `THANKS` still accurate for significant contributions.
- [ ] Same SHA-256 sums documented on both sites if you publish checksums in two places.
- [ ] `git push sourceforge feature/thumbnails-save-ai-wip` (or `main` after release merge) and `git push sourceforge --tags` after the GitHub push (or run the mirror script from the branch you want mirrored).
- [ ] SourceForge **Files** folder for the version contains the same artifacts as GitHub Releases (where applicable).
