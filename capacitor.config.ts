import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "com.dtr.messenger",
  appName: "DTR",
  webDir: "dist",
  server: {
    // The messenger stays hosted on Render; the Android app is its native shell.
    url: "https://dtr-messenger-z1ee.onrender.com",
    cleartext: false,
    allowNavigation: ["dtr-messenger-z1ee.onrender.com"]
  }
};

export default config;
