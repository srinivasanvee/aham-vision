# Google Play release checklist

Package ID: `com.sri.ahamvision` (cannot be changed after the first Play upload)

## One-time Play Console setup

1. Create the app as **aham-vision**, default language English (United States), app/game type App, and choose the appropriate free/paid setting.
2. Enroll in Play App Signing and create/upload the first signed AAB.
3. Host `docs/PRIVACY_POLICY.md` publicly, add a support email, and enter the policy URL in App content and Store settings.
4. Complete Data safety: no data collected or shared by the developer; camera and optional microphone content is processed ephemerally/on-device. Confirm these answers against any future SDK additions.
5. Complete Ads (No), App access (all functionality available without login), Content rating, Target audience, and any required testing declarations.
6. Add a 512×512 high-resolution icon, 1024×500 feature graphic, phone screenshots, and the listing text from `store-listing/en-US/`.
7. Create a Google Cloud service account with Play Console release permissions and save its JSON key.

## GitHub secrets

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`
- `PLAY_STORE_JSON_KEY_BASE64`

Keep the original keystore and passwords in a separate secure backup. Losing an upload key complicates future updates.

## Release flow

```bash
git tag v1.0.0
git push origin v1.0.0
```

The tag workflow builds a signed AAB and uploads it as a draft to the Play internal-testing track. Review the draft in Play Console before rolling it out. Promotion remains an explicit manual gate.
