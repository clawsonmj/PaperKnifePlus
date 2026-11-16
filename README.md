<div align="center">

<img src="icon.png" width="112" alt="PaperKnife Lite icon">

<h1>PaperKnife Lite</h1>

<p><strong>A single-screen PDF page organizer for Android.</strong></p>

<p>100% offline. No accounts, no uploads, no tracking.</p>

</div>

---

PaperKnife Lite is a pared-down build of
[PaperKnife+](https://github.com/potatameister/PaperKnifePlus). It keeps the look
and drops everything except organizing the pages of one PDF.

## What it does

- **See every page** of a PDF as a thumbnail grid, at three zoom levels
- **Read** the PDF in a continuous full-width view with pinch and double-tap zoom
- **Reorder** pages by holding and dragging, or with the Move up / Move down buttons
- **Rotate** and **delete** the pages you select
- **Add pages from image files** (JPEG, PNG, WebP and anything else Android can decode)
- **Save** the result as a new PDF, or over the original

It also opens PDFs sent from other apps ("Open with"), unlocks password-protected
files when you supply the password, and can start a new PDF from images alone.

## How it works

- Thumbnails come from Android's built-in `PdfRenderer`.
- Saving uses [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android). Pages are
  re-linked rather than re-rendered, so text, vectors and image quality are untouched.
  JPEGs are embedded byte-for-byte.
- The app has no `INTERNET` permission.

## Build

```bash
./gradlew assembleRelease
```

Requires JDK 17+, Android SDK platform 36 and build-tools 35.0.0. The APK lands in
`app/build/outputs/apk/release/`. Without a release keystore
(`KEYSTORE_FILE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) it is signed
with the debug key, which is fine for installing on your own devices.

Every push and pull request also builds the APK in GitHub Actions; download it from
the run's **Artifacts**.

- **Android:** 7.0+ (minSdk 24)
- **License:** GPL-3.0-or-later, same as PaperKnife+
