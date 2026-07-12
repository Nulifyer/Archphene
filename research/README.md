# Research archive

This directory preserves the investigations and evidence that led to the current Archphene design. These documents are historical records, not current product documentation or compatibility guarantees.

Use [`docs/`](../docs/README.md) for current architecture, security, development, release, storage, and roadmap documentation.

## Sections

- [Feasibility and design studies](feasibility/README.md): OS architecture alternatives, package/runtime design, and application case studies.
- [Foundation experiments](experiments/foundation/README.md): VM, ELF, loader, glibc, wrapper, and early manager milestones.
- [Bridge experiments](experiments/bridge/README.md): permissions, Storage Access Framework, protocol transport, home, and document brokering.
- [Application experiments](experiments/apps/README.md): KCalc, Mousepad, manager, and ARM64 evidence.
- [Reference implementation reviews](references/README.md): mature Wayland/compositor and app-manager source comparisons.
- [Audits](audits/README.md): implementation gap and risk assessments.
- [Alternative architecture tracks](tracks/README.md): earlier host, VM, and platform approaches.
- [Recovery artifacts](recovery/README.md): scripts and snapshots retained for reproducing source-recovery work.

## Reading research

Research files often include dates, machine-specific paths, intermediate failures, and conclusions that later work superseded. When research conflicts with current documentation, treat `docs/` and the implementation as authoritative, then update the current docs if they disagree.