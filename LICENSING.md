# Licensing

SPDX-License-Identifier: GPL-3.0-only

JPdfBookmarks is free software under the **GNU General Public License, version 3**
(GPL-3.0). The full license text is in the [`COPYING`](COPYING) file in this repository.

## Historical note (SourceForge)

The program was [announced as open source under GPLv3](http://flavianopetrocchi.blogspot.com/2010/01/jpdfbookmarks-has-gone-open-source.html)
and hosted on [SourceForge](https://sourceforge.net/projects/jpdfbookmarks/). The
GPL-3.0 text in `COPYING` matches that lineage. If you redistribute binaries or
modified sources, comply with GPL-3.0 (including source offer and copyright notices).

## Third-party components

Several bundled or linked libraries use their own licenses (for example LGPL/MPL
for parts of the PDF stack). See existing notices in source headers and any
dependency documentation when preparing a distribution.

## Image decoder policy

- `org.apache.pdfbox:jbig2-imageio` (Apache-2.0) is included for JBIG2 image support.
- `com.github.jai-imageio:jai-imageio-jpeg2000` is not bundled because it contains
  `jj2000`-licensed code that is documented by upstream as not GPL-3 compatible.
- If JPEG2000 decoding is needed, users can install a compatible decoder separately
  in their local runtime and accept its license terms independently.
