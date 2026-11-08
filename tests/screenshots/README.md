# EuroRaptor OCR screenshot fixtures

Place original, unmodified Google Maps screenshots here for automated speed-limit sign recognition regression tests.

## Naming

- `limit_030_*.png` — expected speed limit: 30 km/h
- `limit_070_*.png` — expected speed limit: 70 km/h
- `limit_100_*.png` — expected speed limit: 100 km/h
- `limit_130_*.png` — expected speed limit: 130 km/h

Use unique descriptive suffixes, e.g. `limit_100_A58_night.png`. Include cases where the speed-sign badge is at different vertical positions, where Maps is in day/night mode, and where OCR previously failed. Only the speed inside the red-bordered sign is the limit; the adjacent speed number is the current speed.

Keep screenshot dimensions and coordinates unchanged: image resizing or cropping can hide location-dependent bugs. Avoid uploading personal notifications or other private information.

## Current test integration

The existing GitHub Actions workflow `.github/workflows/android.yml` executes `gradle --no-daemon testDebugUnitTest`, but **does not yet read PNG fixtures or run OCR against them**. This folder is a fixture location, not proof that OCR is automatically tested.

To enable real screenshot regression tests, add an Android instrumentation test (or an OCR-compatible test runner) that loads these images, calls the app's speed-sign recognition pipeline, and asserts the expected limits. Android ML Kit image recognition is not automatically exercised by ordinary JVM unit tests.

The four speed limits are the intended coverage; the 70 km/h original screenshot still needs to be provided if missing from the ZIP.
