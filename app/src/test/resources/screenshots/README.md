# Real Google Maps screenshot fixtures

Add the original PNG screenshots here. Create `cases.csv` with one row per image:

```csv
# filename,expected_limit
example_30.png,30
example_100.png,100
```

`RealScreenshotBadgeTest` uses the same pixel-based circle detector as the app and checks that a red speed-limit badge is found. It does **not** yet verify the digits using ML Kit OCR; that requires a separate Android/instrumented test. If `cases.csv` is missing, the real-image test is **skipped**, not counted as passed. If it exists, missing/unreadable images or missing badges fail the test.

Do not commit screenshots containing sensitive personal information without reviewing them first.
