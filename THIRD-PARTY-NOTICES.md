# Third-Party Notices

Crema is licensed under **GPL-3.0-or-later** (see [`LICENSE`](LICENSE)). It bundles
or derives from the third-party material listed below; the upstream copyright
notices and licenses are reproduced here as those licenses require.

## Fonts — SIL Open Font License 1.1

The following fonts are bundled in `android/app/src/main/res/font/` and used by the
Android app (the *Newsreader* face is also the basis for Crema's baked brand mark).
Each is licensed under the **SIL Open Font License, Version 1.1**, reproduced in full
below.

- **Newsreader** — Copyright 2019 The Newsreader Project Authors
  (<https://github.com/productiontype/Newsreader>), with Reserved Font Name "Newsreader".
- **Hanken Grotesk** — Copyright 2020 The Hanken Grotesk Project Authors
  (<https://github.com/hafontia/Hanken-Grotesk>), with Reserved Font Name "Hanken Grotesk".
- **JetBrains Mono** — Copyright 2020 The JetBrains Mono Project Authors
  (<https://github.com/JetBrains/JetBrainsMono>), with Reserved Font Name "JetBrains Mono".

```
-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded,
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
```

## DE1 profiles — Decent Espresso / de1app (GPL-3.0)

Crema's built-in profile corpus (`core/de1-domain/profiles/builtin.json`) is the 88
standard profiles shipped by the **de1app** project — the Decent Espresso DE1 tablet
application (<https://github.com/decentespresso/de1app>) — each converted once from
its upstream `.tcl` form into Crema's native JSON `Profile` model. The upstream
`.tcl` files themselves are not redistributed.

Two upstream `.tcl` profiles are retained verbatim as **parser test fixtures**
(`core/de1-domain/tests/fixtures/best_practice.tcl` and `default.tcl`, marked
`author Decent`), used only to exercise Crema's legacy-Tcl profile import. de1app is
licensed GPL-3.0; Crema is GPL-3.0-or-later, so this use is license-compatible. See
[`core/de1-domain/profiles/README.md`](core/de1-domain/profiles/README.md).

## BOOKOO Motto 80 protocol — BOOKOO (MIT) and Sabotage1 (GPL-3.0-or-later)

Crema's Motto 80 grinder driver (`core/de1-grinder/src/bookoo_motto80.rs`) ports the
framing, handshake and message model of BOOKOO's published Bluetooth protocol and its
example client (`bookoo_motto80/README.md` and `bookoo_motto80/mt80_gatt_client.py` in
<https://github.com/BooKooCode/OpenSource>, commit `76458a1`; documentation Copyright
© 2024 DGSSL BOOKOO TECH LTD.). The driver's test vectors use the README's example
messages. That repository is licensed under the MIT License:

```text
MIT License

Copyright (c) 2024 BooKoo

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

The driver also follows the decaid Motto 80 plugin by Roy Ackerman
(<https://github.com/Sabotage1/decaid-bookoo-motto80>, v0.1.0, commit `6a09335`,
Copyright (C) 2026 Roy Ackerman, GPL-3.0-or-later) for its name match, firmware gate,
field ranges and timeouts. GPL-3.0-or-later, like Crema.

## Built-in brew recipes — credits

Crema's built-in guided-brew recipes (`core/de1-domain/recipes/builtin.json`) are
published recipes, each shown in the app with a credit line and a link to its primary
source. The step plans restate each author's published technique; no text, images,
video or logos are reproduced. Crema is not affiliated with, or endorsed by, any of
these authors or companies.

- James Hoffmann — A Better 1 Cup V60 Technique (2022); The Ultimate V60 Technique
  (2019); The Ultimate AeroPress Technique (2021); The Ultimate French Press Technique
  (2016); The Ultimate Clever Dripper Technique (2020, water-first method credited by
  Hoffmann to Workshop Coffee); Everything I Learned About Cold Brew Coffee (2025);
  adapted from The Ultimate Moka Pot Technique (2022).
- Tetsu Kasuya — 4:6 Method (2016 World Brewers Cup Champion), via Hario.
- AeroPress Inc. — official brewing instructions.
- Tuomas Merikanto — 2021 World AeroPress Champion recipe, via AeroPress.
- Stumptown Coffee Roasters — Chemex and Kalita Wave brew guides.
- Hario — Syphon brew guide.

Source links for every recipe are listed in
[`core/de1-domain/recipes/README.md`](core/de1-domain/recipes/README.md).
