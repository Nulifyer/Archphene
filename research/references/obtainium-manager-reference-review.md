# Obtainium reference review for the Linux app manager

Date: 2026-07-12
Reference revision: `ImranR98/Obtainium` commit `3e29ddca554084458dfd11f3b605aff842195b04`
Physical reference: Obtainium 1.6.3 (`versionCode 23423`) on the attached Samsung Galaxy

Greenfield follow-up: 2026-07-30, against the current upstream `main`
`app_list_tile.dart`, repository screenshots, and GPL-3.0 license.

The original July 12 notes below describe the retained design lessons and the
now-replaced Java prototype. The Rust/Kotlin follow-up is the current Archphene
decision where the two differ.

## Why Obtainium is relevant

Obtainium treats an application as a persisted source configuration plus transient download, installed-package, and update state. A source adapter normalizes a URL, retrieves release metadata, chooses compatible assets, and returns a new immutable app record. Update checks are batched; installer implementations are selected behind a common interface; periodic checks run through Android WorkManager; and Android remains responsible for installation authorization.

Archphene needs the same orchestration shape, but the artifact pipeline is different. Obtainium discovers prebuilt Android APK assets. Archphene must resolve a signed Arch package and dependency closure, validate the repository trust chain, construct a deterministic wrapper APK, preserve its Android signer, and then ask Android to install it.

## UI findings

Obtainium's phone layout uses:

- a large page title with correct edge-to-edge system insets;
- a search field with count and filter controls;
- a prominent batch install/update action;
- compact app rows containing icon, name, publisher/source, version, and update state;
- a floating Add action;
- Apps and Settings bottom navigation;
- automatic light/dark Material palettes;
- app details as a separate route;
- navigation rail at 600 dp and a list/detail layout at 900 dp.

The updated Archphene manager adopts the title, search/filter surface, batch update action, app cards, details view, two-tab navigation, system-inset handling, automatic dark palette, and a 720 dp content cap on wide displays. It deliberately does not show an Add action yet: adding a package would be misleading until wrapper generation and signer continuity exist.

## Backend findings adopted

### Source adapters

Obtainium registers one `AppSource` implementation per release host. Archphene now has a `PackageSourceAdapter` boundary and a strict `ArchLinuxSourceAdapter`. It accepts only official `https://archlinux.org/.../json/` package metadata, rejects redirects and unexpected content types, caps metadata at 1 MiB, and records the resulting version in persistent manager state.

Arch Linux ARM still requires a separate adapter. Its package pages are not interchangeable with Arch Linux's JSON endpoint, so the manager must not parse both through one permissive scraper.

### Persistent and transient state

Obtainium persists one atomic JSON record per app and keeps download progress in memory. Archphene now persists available version, last-check time, update state, and errors per Android package through `ManagerStateStore`; checking/progress remains transient through `LinuxAppUpdateCoordinator`.

SharedPreferences is adequate for the current small installed-wrapper catalog. Before user-added sources, migrate to atomic per-app records or a transactional database with schema versions, corrupt-record quarantine, import/export, and rollback.

### Batched update checks

Obtainium deduplicates active checks, batches persistence, limits concurrency, and exposes aggregate progress. Archphene now uses a bounded three-thread coordinator, exposes per-app and aggregate completion, persists each result, and restores it after process death. A production implementation should add request coalescing, cancellation, retry/backoff, network metering policy, and source-specific rate limits.

### Background work

Obtainium uses WorkManager and notifications. The no-dependency Archphene prototype uses a persisted `JobScheduler` job constrained to network availability, runs daily, and requests `POST_NOTIFICATIONS` only when the user enables background checks. It checks metadata but never installs silently.

### Installation

Obtainium supports stock, Shizuku, and external installer strategies. Archphene retains Android `PackageInstaller` only. This matches the project goal: Android must mediate install confirmation and signer/package identity. Shizuku-style privileged installation is not required and would weaken the default permission model.

### Self-update behavior

Obtainium seeds its GitHub project URL as an ordinary tracked app on the first non-F-Droid run. It sends its own APK through the same GitHub source, asset filtering, download, signature/package, and stock Android installer path as every other tracked app. It moves Obtainium to the end of a batch so replacing the running process cannot interrupt updates for other apps, and it refuses silent self-install even when silent replacement would otherwise be available.

After process restart, Obtainium reloads Android's real installed package information and reconciles it with persisted app metadata. Archphene uses the same lifecycle principle while keeping its manager entry synthetic and non-removable: catalog load reads the manager's actual Android version and now clears stale checking or update-available state when the installed version equals the previously discovered version. Android confirmation remains mandatory for manager replacement.

Obtainium represents download progress as 0 through 100 and installation as an indeterminate sentinel, replacing the row's normal trailing state with a progress control. Archphene now follows the same single-state principle for self-update: the version action becomes an Updating spinner while the richer download/install stage indicator remains available below it.

The Rust/Kotlin manager follow-up keeps that useful immediate-feedback pattern
without copying Obtainium's Flutter implementation. Obtainium's current compact
row replaces its normal trailing version state with a bounded linear progress
track, percentage/byte labels, and optional cancellation while that specific
app is active. Archphene now gives only the matching or appended package row a
recycled native Android progress track, retains its exact operation, phase, and
percentage label, and keeps the durable multi-line activity card for recovery
details. Zero percent is indeterminate so a newly queued operation is visibly
active before useful byte progress exists; later phases are determinate.

This is intentionally narrower than Obtainium's presentation:

- no category gradients, swipe-to-install/remove, publisher icons, or source
  branding were copied;
- Archphene retains explicit buttons because package closure review, mutation,
  and Android launcher publication have different trust boundaries;
- terminal progress stays durable across restart rather than existing only as
  an in-memory download notifier;
- one native `ProgressBar` belongs to each recycled visible row, and progress
  updates reuse it rather than rebuilding the package list.

State-preserving exact-ABI gates prove the matching row and activity card show
the same queued state, the row exposes accessible progress, Cancel remains
available before mutation, and the real package database, cache, job state, and
manager navigation are restored. Full-device light and dark captures were
inspected on the x86_64 emulator and AArch64 Samsung.

## Security and licensing conclusions

Obtainium's source is GPL-3.0. This work reimplements architectural ideas and
Android interaction patterns in Archphene's Rust/Kotlin manager; it does not
copy Obtainium Dart/Flutter code, assets, branding, or translations. Directly
incorporating its implementation would require a deliberate GPL-3.0
compatibility decision for the distributed combined work.

The manager still must not treat package metadata as sufficient authorization to install. A production transaction requires repository signature verification, exact closure hashes, deterministic wrapper construction, signer continuity, package identity checks, downgrade policy, atomic downloads, and cleanup/rollback.

## Remaining milestones

1. Add an Arch Linux ARM source adapter backed by authenticated repository metadata.
2. Persist user-selected package sources independently of installed wrappers.
3. Implement host-side deterministic package closure resolution and wrapper generation.
4. Define per-user or repository APK signing and recovery policy.
5. Feed verified generated APK metadata into the existing `ApkUpdateInstaller` transaction.
6. Add import/export, categories, update history/logs, and notification deep links.
7. Replace the centered wide layout with navigation rail and list/detail panes when the manager becomes a desktop-mode workflow.

## Upstream references

- Repository, screenshots, and license:
  <https://github.com/ImranR98/Obtainium>
- Current compact app row:
  <https://github.com/ImranR98/Obtainium/blob/main/lib/components/app_list_tile.dart>
- Current application list:
  <https://github.com/ImranR98/Obtainium/blob/main/lib/pages/apps.dart>
- GPL-3.0 license:
  <https://github.com/ImranR98/Obtainium/blob/main/LICENSE.txt>
