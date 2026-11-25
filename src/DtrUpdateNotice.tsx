import { useEffect, useState } from "react";

type ReleaseAsset = { name: string; browser_download_url: string };
type Release = { tag_name: string; html_url: string; body?: string; assets: ReleaseAsset[] };

const CURRENT_VERSION = "0.7.2";
const REPOSITORY = "Hqhsha/-dtr-messenger";

export default function DtrUpdateNotice() {
  const [release, setRelease] = useState<Release | null>(null);
  const [dismissed, setDismissed] = useState(false);

  useEffect(() => {
    const capacitor = (window as Window & { Capacitor?: { isNativePlatform?: () => boolean } }).Capacitor;
    if (!capacitor?.isNativePlatform?.()) return;
    let active = true;
    fetch(`https://api.github.com/repos/${REPOSITORY}/releases/latest`, {
      headers: { Accept: "application/vnd.github+json" },
      cache: "no-store"
    })
      .then(response => {
        if (!response.ok) throw new Error("Release check unavailable");
        return response.json() as Promise<Release>;
      })
      .then(data => {
        const latest = data.tag_name.replace(/^v/, "");
        if (active && latest !== CURRENT_VERSION && data.assets?.some(asset => asset.name.toLowerCase().endsWith(".apk"))) {
          setRelease(data);
        }
      })
      .catch(() => {});
    return () => { active = false; };
  }, []);

  if (!release || dismissed) return null;
  const apk = release.assets.find(asset => asset.name.toLowerCase().endsWith(".apk"));
  if (!apk) return null;

  return (
    <div role="status" style={{
      position: "fixed", zIndex: 10000, left: 14, right: 14, bottom: "max(14px, env(safe-area-inset-bottom))",
      maxWidth: 520, margin: "0 auto", padding: 16, borderRadius: 18,
      background: "rgba(24, 20, 35, .97)", border: "1px solid rgba(168, 85, 247, .55)",
      boxShadow: "0 12px 40px rgba(0,0,0,.4)", color: "#fff", backdropFilter: "blur(18px)"
    }}>
      <div style={{display:"flex",justifyContent:"space-between",alignItems:"center",gap:12}}>
        <strong>Доступно обновление DTR</strong>
        <button onClick={() => setDismissed(true)} aria-label="Закрыть" style={{background:"transparent",border:0,color:"#c4b5fd",fontSize:22}}>×</button>
      </div>
      <p style={{margin:"8px 0 12px",color:"#d4d4d8",fontSize:14}}>Новая версия {release.tag_name}. Скачай APK и подтверди установку Android.</p>
      <a href={apk.browser_download_url} target="_blank" rel="noreferrer" style={{
        display:"block",textAlign:"center",padding:"11px 14px",borderRadius:12,
        background:"linear-gradient(100deg,#7c3aed,#a855f7)",color:"#fff",fontWeight:700,textDecoration:"none"
      }}>Скачать обновление</a>
      <a href={release.html_url} target="_blank" rel="noreferrer" style={{display:"block",marginTop:9,textAlign:"center",color:"#c4b5fd",fontSize:12}}>Что нового</a>
    </div>
  );
}
