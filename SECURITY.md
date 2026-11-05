# Security

Thank you for taking the time to look after Zonk-Core's users.

## Report a problem privately

Please don't post security problems in a public issue.

1. Open the repo's **Security** tab on GitHub.
2. Choose **Report a vulnerability**
   (direct link: https://github.com/karolek13245/zonk-core/security/advisories/new).
3. Say what you found, which version, and how to repeat it. A short description is enough.

If that button isn't there, open a normal [issue](https://github.com/karolek13245/zonk-core/issues) that says only
"I have a security report" (no details), and the owner will get in touch.

Never paste a real API key, token or password into a report or an issue. If you found one in the code,
say which file and line, not its value.

## What is protected

- **Your API key** is stored encrypted. The lock for it lives in the Android Keystore, and the key is cleaned before it is
  sent and removed from error messages.
- **Your chats and pictures** stay on your phone, in the app's private storage.
- **On-device mode** sends nothing anywhere. **Cloud mode** only talks to the `https://` server address you set.
- **The update check** only reads this repo's public release list over `https://` and only ever opens this repo's release pages.
  It never installs anything by itself.
- **Models** are downloaded over `https://` from the addresses in the app's catalog.

## What is not covered

- A phone that someone fully controls (rooted, or infected with malware that has that level of access).
- The servers you point Cloud mode at: their owners decide how messages are handled.
- Releases are debug builds signed with a debug key. Download only from this repo's releases page.

## Supported versions

Only the latest release gets fixes.
