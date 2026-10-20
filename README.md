# TikTok TV (Sleppify)

**Android TV / Google TV** için, kumanda ile kullanılmak üzere hazırlanmış resmi olmayan TikTok sarmalayıcısı. TikTok'un resmi TV uygulamasını Haziran 2025'te kaldırmasından doğdu.

> Español: [README.es.md](README.es.md)

## Özellikler
- **D-Pad ile gezinme:** ↑/↓ video değiştirir, →/← yan butonlara (beğen, yorum, paylaş) geçer ve oradayken ↑/↓ butonlar arasında dolaşır.
- **Medya tuşları:** Oynat/Durdur, ⏪/⏩ ile ±10 sn sarma, CH ±/PgUp/PgDn ve ⏮/⏭ ile önceki/sonraki video. → veya ← tuşunu basılı tutmak da 10 sn sarar.
- **Sanal mouse:** **OK** tuşuna uzun basınca eylemsizlikli imleç açılır; kapatmak için OK'a 1,5 sn bas (veya MENU). Gerçek mouse hover olayı gönderir (video kontrolleri, ilerleme çubuğu, paylaş menüsü açılır). OK basılı tutup imleci oynatmak sürükleme yapar. CH ± / PgUp / PgDn imlecin altındaki alanı (yorumlar vb.) kaydırır. İsteğe bağlı: Ayarlar → *imleç ekran kenarına dayanınca kaydır* (kenarda ~0,5 sn beklemek gerekir).
- **Temiz ekran:** Yeşil tuş (ya da menüden 🧼) yan butonları, açıklamayı, ilerleme çubuğunu, üst menüyü ve sol menüyü gizler, sadece video kalır. BACK veya → ile kapanır.
- **Menü (MENU tuşu):** Ara, Sana Özel / Takip / Keşfet / CANLI sekmeleri, sanal mouse, **QR kod ile giriş**, sesi aç/kapat, ayarlar, tuş yardımı.
- **QR ile giriş:** Google, TV WebView'lerinde girişi engellediği için telefondaki TikTok uygulamasıyla QR tarayarak giriş yap.
- **Uygulama içi sesli arama:** Menüden **🎤 Sesli ara** (ya da kumandanın mikrofon/SEARCH tuşu uygulamaya ulaşıyorsa). Dinleme ekranında konuştukça yazı görünür; **OK** bitirir, **BACK** iptal eder. Dinlerken video durur. Cihazda tanıma servisi yoksa sistemin ses penceresi kullanılır. İlk kullanımda mikrofon izni istenir.
- **Doğal cümle:** "futbol videoları göster", "show me football videos", "muéstrame videos de fútbol" gibi cümlelerden dolgu kelimeler atılır ("futbol" aranır), sonuçlar TikTok'un **Videolar** sekmesinde açılır ve ilk video otomatik oynar (Ayarlardan kapatılabilir). Sonraki sonuçlara ↑/↓ ile geçilir.
- **Sistem sesli araması:** "TikTok'ta … ara" komutları (`SEARCH` / `MEDIA_PLAY_FROM_SEARCH`) doğrudan arama sayfasını açar.
- **Otomatik sonraki video** (ayarlardan açılır): video bitmeden hemen önce bir sonrakine geçer.
- **Ayarlar:** mouse hızı, otomatik sonraki video, telemetri/reklam engelleme, H.264'ü zorlama (AV1 kapatma), açılış sayfası, çıkış yap / verileri temizle.
- **Dayanıklılık:** ağ gelince hata ekranından otomatik yeniden yükleme, WebView çökmesinde yeniden başlatma, Home'a basınca sesin durması, oturum çerezlerinin diske yazılması.
- **Güvenlik:** ana çerçeve yalnızca TikTok / CDN / giriş sağlayıcılarına gidebilir (diğer bağlantılar engellenir), HTTP kapalı, dosya erişimi kapalı.
- **Diller:** English (varsayılan), Türkçe, Español — `res/values*/strings.xml`.

## Kumanda
| Tuş | İşlev |
|---|---|
| ↑ / ↓ | Önceki / sonraki video |
| → / ← | Etkileşim alanına gir / çık (basılı tut: ±10 sn sar) |
| OK | Oynat / durdur (yan butondayken tıklar) |
| OK uzun | Sanal mouse aç (açıkken kapatmak için 1,5 sn) |
| 🎤 / SEARCH | Sesli arama |
| 🔴 Kırmızı | Mouse aç / kapat (basılı tut: ayarlar) |
| 🟢 Yeşil | Temiz ekran (sadece video) aç / kapat |
| ⏯ ⏪ ⏩ | Oynat-durdur / sar |
| CH ±, PgUp/PgDn, ⏮ ⏭ | Önceki / sonraki video (mouse açıkken: kaydır) |
| MENU | Menü |
| BACK | Kapat / geri |

## Gereksinimler
**Android 8.0 (API 26)** veya üstü Android TV / Google TV.

## Kurulum
- **Hızlı:** [Releases](../../releases) sayfasından APK'yı indir ve kur ("bilinmeyen kaynaklar" açık olmalı).
- **Derleme:** depoyu klonla, Android Studio'da aç (JDK 21), TV cihazına yükle. `./gradlew testDebugUnitTest assembleRelease`.

### İmzalı release APK
`keystore.properties` dosyası (git'e eklenmez) veya ortam değişkenleri ile:

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

GitHub Actions (`.github/workflows/build.yml`) her push'ta testleri çalıştırıp APK üretir. `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` secret'ları eklenirse APK imzalanır; `v*` etiketi atılırsa APK GitHub Release'e eklenir.

## Yapı
- `MainActivity.kt` — WebView, yaşam döngüsü, tuş yönetimi, menü/ayarlar, ağ ve çökme yönetimi.
- `VirtualMouse.kt` — sanal imleç, dokunma olayları, kenarda kaydırma.
- `UrlPolicy.kt` — izinli adresler, tracker listesi, Google giriş tespiti (JVM'de test edilir: `UrlPolicyTest`).
- `AppSettings.kt` — SharedPreferences sarmalayıcısı.
- `assets/tiktok_tv_bridge.js` — JS köprüsü: gezinme, medya kontrolü, otomatik sonraki video, modal kapatma, video ayarı, `selfTest()`.
- `TikTokLoaderView.kt` — yükleme animasyonu.

## Sorun giderme
- **TikTok güncellenince bir şey bozulursa:** `adb logcat -s TikTokTV` çıktısında `bridge self-test: video=…;feed=…;active=…;sidebar=…` satırına bak; `0` olanlar bozulan seçicilerdir (`tiktok_tv_bridge.js`, `FEED_ITEM_SELECTOR` / `SIDEBAR_SELECTOR`).
- **Feed yüklenmezse:** Ayarlardan telemetri engellemeyi kapat.
- **Video siyah gelirse:** `MainActivity.setupWebView()` içinde `LAYER_TYPE_NONE` → `LAYER_TYPE_HARDWARE`.
- **Bir CDN HTTP üzerinden geliyorsa ve oynatma bozulduysa:** `mixedContentMode` değerini `MIXED_CONTENT_ALWAYS_ALLOW` yap ve manifestte `usesCleartextTraffic`'i aç.

## Uyarı
Kişisel, resmi olmayan bir proje; TikTok ile bağlantısı yoktur. Kullanım sorumluluğu sana aittir.
