# Third-party notices

SumBuddy is licensed under the GNU General Public License, version 3 or any later version (`GPL-3.0-or-later`),
with the additional permission given in [README.md](README.md#licence). The parts below come from others and keep
their own licences.

## Fonts

Bundled in the app (`app/src/main/res/font/`) and in the friends' page (`server/web/public/fonts/`). Each is under
the SIL Open Font License, Version 1.1. The full licence texts are in `app/src/main/assets/licenses/` (shipped inside
the APK) and `server/web/public/fonts/`.

| Font | Copyright | Licence text |
|---|---|---|
| DM Sans | Copyright 2014 The DM Sans Project Authors (https://github.com/googlefonts/dm-fonts) | `OFL-DMSans.txt` |
| Bricolage Grotesque | Copyright 2022 The Bricolage Grotesque Project Authors (https://github.com/ateliertriay/bricolage) | `OFL-BricolageGrotesque.txt` |
| Roboto (app only, for the "Continue with Google" label) | Copyright 2011 The Roboto Project Authors (https://github.com/googlefonts/roboto-classic) | `OFL-Roboto.txt` |

## Line icons: Feather

The line icons (`design/icons/svg/*_line.svg`, the app's `ic_*_line.xml`, `ic_nav_*.xml` and `ic_back.xml`) are
based on Feather (https://feathericons.com). The licence is also in `app/src/main/assets/licenses/MIT-Feather.txt`.

```
The MIT License (MIT)

Copyright (c) 2013-2023 Cole Bemis

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

## The Google "G" logo

`app/src/main/res/drawable/ic_google_logo.xml` and `design/spec/components/Google-Button.*` show the Google "G" logo,
a trademark of Google LLC. It is used only on the "Continue with Google" button, under Google's sign-in branding
guidelines, which ask for it there. It is not covered by the GPL and may be used only under Google's terms.

## Proprietary libraries in the app

The Android app links these libraries, which are distributed under the Android Software Development Kit License, not
an open-source licence. The additional permission in [README.md](README.md#licence) names the Google Play services
client libraries, Firebase Cloud Messaging, the Google Identity Services library (googleid) and the Credential Manager
Play services module. The two Firebase libraries at the end of this list come in with Firebase Cloud Messaging.

- Google Play services client libraries (`play-services-auth`, `-auth-base`, `-auth-api-phone`,
  `-auth-blockstore`, `-fido`, `-identity-credentials`, `-base`, `-basement`, `-tasks`, `-cloud-messaging`,
  `-stats`), pulled in by the Credential Manager Play services module and Firebase Cloud Messaging;
- Google Identity Services for Android (`com.google.android.libraries.identity.googleid:googleid`);
- two Firebase libraries, `com.google.firebase:firebase-iid-interop` and
  `com.google.firebase:firebase-measurement-connector`, pulled in by Firebase Cloud Messaging.

Firebase Cloud Messaging (`firebase-messaging`) and the Credential Manager modules are Apache-2.0 themselves. Every
other library in the app is open source under Apache-2.0, except one:
`androidx.datastore:datastore-preferences-external-protobuf` carries a copy of Protocol Buffers (Copyright 2008 Google
Inc.), which is under the BSD 3-Clause licence. Its licence text ships in the APK, in
`META-INF/androidx/datastore/datastore-preferences-external-protobuf/LICENSE.txt`.

```
Copyright 2008 Google Inc.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of the copyright holder nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

The server's one runtime library, `jose`, is under MIT. `gradle/libs.versions.toml`, `server/package.json` and
`server/web/package.json` list the direct dependencies.

## Names

Splitwise, Google, Google Pay, WhatsApp, UPI and the bank, card and merchant names in the code and tests belong to
their owners. They are used only to say what SumBuddy works with. SumBuddy is not affiliated with or endorsed by any
of them.
