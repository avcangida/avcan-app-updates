# AVCAN GIDA – Bilgisayar Excel Aktarım Paneli

## Bu sürümde eklenenler
- `web-admin/index.html`: bilgisayarda çalışan web yönetici paneli.
- `functions/src/index.ts`: `importPriceChanges` adlı güvenli aktarım fonksiyonu.
- Excel dosyası tarayıcıda okunur; veriler doğrudan istemciden Firestore'a yazılmaz.
- Sunucu fonksiyonu yönetici rolünü doğrular, satırları kontrol eder ve kayıtları `priceChanges` koleksiyonuna şubelere dağıtır.
- Dağıtım türleri: Excel şube kodu, manuel şube seçimi, birleşik dağıtım.
- `imports` koleksiyonuna aktarım geçmişi yazılır.
- Fotoğraf yükleme bu sürümde kapsam dışıdır.

## 1. Firebase Web uygulaması ekle
Firebase Console > Project settings > Your apps > Add app > Web.
Bir takma ad verin (ör. `AVCAN Web Yönetim`) ve kayıt işlemini tamamlayın.
Ekranda gösterilen `firebaseConfig` değerlerini `web-admin/index.html` içindeki `firebaseConfig` nesnesine girin:
- `apiKey`
- `authDomain`
- `projectId`
- `storageBucket`
- `messagingSenderId`
- `appId`

Android `google-services.json` dosyası web `appId` değerini içermez; bu nedenle Web uygulamasını ayrıca kaydetmek gerekir.

## 2. Cloud Functions dağıtımı
Ön koşullar:
- Firebase CLI kurulmuş olmalı.
- Proje sahibi/dağıtım yetkili Google hesabıyla `firebase login` yapılmalı.
- Cloud Functions için Firebase projesi Blaze (kullandıkça öde) planında olmalıdır. Dağıtımdan önce bütçe uyarısı belirleyin.
- Komutları proje ana klasöründe çalıştırın.

```bash
firebase use avcan-gida-fiyat-takip
cd functions
npm install
npm run build
cd ..
firebase deploy --only functions
```

Fonksiyon `europe-west1` bölgesine dağıtılır. Web panel de aynı bölgedeki callable fonksiyonu çağırır.

## 3. Web panelini çalıştırma
`web-admin/index.html` dosyasını bir statik web sunucusunda yayınlayın (Firebase Hosting önerilir). Örneğin proje kökünde Hosting yapılandırması ayrıca yapılabilir; ilk test için yerel statik sunucu da kullanılabilir.
Firebase Authentication > Sign-in method içinde Email/Password açık olmalıdır.
Yönetici hesabı `users/{uid}` belgesinde `role: "admin"` (veya `owner_admin`) ve `approvalStatus: "approved"` olmalıdır.

## 4. Excel formatı
Başlıklar:
- `Şube Kodu`
- `Barkod`
- `Ürün Adı`
- `Eski Fiyat`
- `Yeni Fiyat`

Şube kodları: `10`, `20`, `40`, `41`, `95`, `96`.
Barkod metin olarak tutulmalıdır; baştaki sıfırlar kaybolmamalıdır.
Her aktarımda en fazla 1000 kaynak satırı ve şubelere dağıtılmış toplam 4000 kayıt kabul edilir.

## 5. Test
Önce 2-3 satırlık kopya bir Excel dosyasıyla test edin. Panelde ön izlemeyi kontrol edin ve aktarın. Ardından Android uygulamasında yönetici hesabıyla Ürünler sekmesini yenileyin.
Manuel dağıtımda şube kodu sütunu kullanılmaz; seçilen hedef şubeler kullanılır.
Birleşik dağıtımda her satırın Excel şubesi ve ayrıca seçilen hedef şubeler birleştirilir (aynı şube iki kez yazılmaz).

## Güvenlik notları
- Web paneli Firebase Authentication ile giriş yaptırır.
- Aktarım yetkisi Cloud Function içinde sunucu tarafında kontrol edilir.
- Callable fonksiyon Firestore Admin SDK kullandığından Firestore istemci yazma kurallarını açmaya gerek yoktur.
- Mevcut Firestore kurallarınızı sırf bu panel için herkese açık hale getirmeyin.
- `google-services.json` Android istemci yapılandırmasıdır; servis hesabı özel anahtarı değildir. Servis hesabı JSON anahtarını web klasörüne veya ZIP'e koymayın.
