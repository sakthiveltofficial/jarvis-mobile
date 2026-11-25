// Config plugin for the Jarvis bridge module.
// The module's own AndroidManifest.xml (merged at build time) declares the services, receivers,
// Shizuku provider and permissions. This plugin only changes app-level settings:
//  - plain ws:// to the desktop on the LAN / Tailscale (frames are end-to-end encrypted with AES-GCM)
//  - singleTask main activity, so settings round trips don't stack copies of the app
//  - release signing from environment variables (CI), falling back to the debug key
const { withAndroidManifest, withAppBuildGradle, AndroidConfig } = require('expo/config-plugins');

const SIGNING = `
        release {
            if (System.getenv("JARVIS_KEYSTORE")) {
                storeFile file(System.getenv("JARVIS_KEYSTORE"))
                storePassword System.getenv("JARVIS_KEYSTORE_PASSWORD")
                keyAlias System.getenv("JARVIS_KEY_ALIAS") ?: "jarvis"
                keyPassword System.getenv("JARVIS_KEY_PASSWORD") ?: System.getenv("JARVIS_KEYSTORE_PASSWORD")
            }
        }`;

function withJarvisManifest(config) {
  return withAndroidManifest(config, (cfg) => {
    const app = AndroidConfig.Manifest.getMainApplicationOrThrow(cfg.modResults);
    app.$['android:usesCleartextTraffic'] = 'true';
    const main = AndroidConfig.Manifest.getMainActivity(cfg.modResults);
    if (main) main.$['android:launchMode'] = 'singleTask';
    return cfg;
  });
}

function withJarvisSigning(config) {
  return withAppBuildGradle(config, (cfg) => {
    let g = cfg.modResults.contents;
    if (!g.includes('JARVIS_KEYSTORE')) {
      g = g.replace(/signingConfigs\s*\{/, (m) => m + SIGNING);
      g = g.replace(
        /(release\s*\{[^}]*?)signingConfig signingConfigs\.debug/,
        '$1signingConfig System.getenv("JARVIS_KEYSTORE") ? signingConfigs.release : signingConfigs.debug',
      );
    }
    cfg.modResults.contents = g;
    return cfg;
  });
}

module.exports = function withJarvisBridge(config) {
  return withJarvisSigning(withJarvisManifest(config));
};
