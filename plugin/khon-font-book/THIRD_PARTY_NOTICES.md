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

## Built-in parser

The plugin contains an isolated metadata worker and a Linux x86_64 decoder.
The worker's libraries are loaded only in its separate Java process.

| Component | Version | Terms |
| --- | --- | --- |
| Apache FontBox | 2.0.34 | Apache-2.0; https://pdfbox.apache.org/ |
| Apache Commons Logging | 1.3.5 | Apache-2.0; https://commons.apache.org/proper/commons-logging/ |
| WOFF2 | 1.0.2 | MIT; https://github.com/google/woff2 |
| Brotli | 1.2.0 | MIT; https://github.com/google/brotli |
| GCC libstdc++, libgcc, libgcc_eh and GCC startup code | 13.3.0, Ubuntu 13.3.0-6ubuntu2~24.04.1 | GPLv3 with GCC Runtime Library Exception 3.1 |
| glibc startup objects | 2.39, Ubuntu 2.39-0ubuntu8.9 | LGPL-2.1-or-later with the startup sources' linking exception |

FontBox's cmap reader is modified to reject invalid glyph indices rather than
silently skip them. WOFF2 uses the existing portable byte access branches and a
64 MiB expanded-stream ceiling in place of its compression-ratio heuristic.
Original headers are retained. Corresponding parser Java/native sources and build
entry points are included in the source bundle, along with the exact Java
dependency source JARs. Each Java library's original LICENSE/NOTICE is preserved
under the worker JAR's `META-INF/licenses/` directory. Native and compiler runtime
statements are under the plugin's `META-INF/licenses/font-parser/` directory.

The decoder uses unmodified GCC runtime archives and a normal GCC compilation
process; see https://gcc.gnu.org/onlinedocs/libstdc++/manual/license.html.
It dynamically uses the system glibc and loader instead of redistributing them.
The linked glibc startup source files carry an additional compiled-linking
permission. The source bundle includes the decoder source and its build script;
compiler package versions are given above. No compiler is invoked at runtime.

The measured runtime is ordinary Halo 2.27.0 on Linux x86_64 with glibc >=2.38,
Java 21 and system `/usr/bin/prlimit`, plus writable/executable temporary storage.
Other platforms are not verified. Python, FontTools, pip and external interpreter
paths are not used. No parsing components are downloaded during installation,
startup or import. User fonts are not bundled.

## Fonts and artwork

No user font binaries are included in the plugin or source ZIP.
Users must have rights to upload and publicly host their
fonts. A metadata embedding-rights flag or a filled license field is not legal
verification. The cover is the author-supplied KFBCover.png artwork, not a
font-file distribution.
