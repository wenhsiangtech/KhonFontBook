# KhonFontBook third-party notices

KhonFontBook retains the project's existing GPL-3.0 declaration. The full project
license is at the JAR root as `LICENSE`. Third-party terms remain their own.
No third-party component is represented as original KhonChi code.

## Java components included in the plugin JAR

| Component | Locked version | License / upstream |
| --- | --- | --- |
| ph-css | 8.2.3 | Apache-2.0; https://github.com/phax/ph-css |
| ph-annotations, ph-base, ph-cache, ph-collection, ph-io, ph-mime, ph-statistics, ph-text, ph-typeconvert | 12.5.1 | Apache-2.0; https://github.com/phax/ph-commons |
| jspecify | 1.0.1 | Apache-2.0; https://github.com/jspecify/jspecify |

Available original LICENSE and NOTICE files are copied from resolved runtime JARs into
`META-INF/licenses/<group>.<artifact>-<version>/`. The source distribution includes
the exact Maven source JARs in `third-party-sources/`; Maven Central also provides
their POMs and sources. Third-party sources are not relicensed as GPL-only code.
JSpecify's Maven JAR omits a license file; its unmodified LICENSE from upstream
tag v1.0.1 is included under its component directory.

## Host and frontend

Halo 2.27.0 and its `@halo-dev/api-client`, `@halo-dev/components`, and
`@halo-dev/ui-shared` SDKs are GPL-3.0 projects (https://github.com/halo-dev/halo).
SLF4J is supplied by Halo; its API and logging implementation are not bundled.
The UI uses the official ESM host-sharing format. Vue 3.5.44 (MIT), Axios 1.20.0
(MIT), and the Halo SDKs 2.27.0 are the locked build-side versions; shared runtime
versions are provided by the Halo host. The source ZIP contains the pnpm lockfile.
This inventory does not imply the host or the entire node_modules tree is bundled
inside this JAR. Build tools are declared in ui/package.json and ui/pnpm-lock.yaml.

## External parser prerequisite (not included in the plugin JAR)

The site administrator separately installs Linux Python >=3.11, FontTools 4.67.0
(MIT, https://github.com/fonttools/fonttools), and Brotli 1.2.0
(MIT, https://github.com/google/brotli). FontTools also ships LICENSE.external;
retain both files in any separately redistributed parser environment. Python
retains its own PSF and bundled component terms. The example Dockerfile and pinned
requirements describe installation; this release does not distribute that image.

## Fonts and artwork

No user font binaries or font interpreter are included in
the plugin or source ZIP. Users must have rights to upload and publicly host their
fonts. A metadata embedding-rights flag or a filled license field is not legal
verification. The cover is the author-supplied KFBCover.png artwork, not a
font-file distribution.
