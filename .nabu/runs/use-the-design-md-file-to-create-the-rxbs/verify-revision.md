# verify.sh fix: sdkVersion → minSdkVersion

Line 57 checks for `^sdkVersion:'26'` but aapt2 (not the older aapt) outputs `minSdkVersion:'26'`. The APK is correct — the badging dump shows:

```
minSdkVersion:'26'
targetSdkVersion:'35'
```

The grep pattern needs to match `minSdkVersion` instead of `sdkVersion`:

```diff
-expect "^sdkVersion:'26'" "minSdk is not 26"
+expect "^minSdkVersion:'26'" "minSdk is not 26"
```
