# AVCAN GIDA – Canlı sisteme geçiş kontrol listesi

Bu paket kaynak kod ve Firebase başlangıç yapılandırmasıdır. Firebase projesine dağıtılmış değildir; çalışan çevrimiçi sistem veya imzalı APK değildir.

## Hazır
- Altı şubenin sabit kod/ad listesi
- Kotlin + Jetpack Compose Android başlangıç arayüzü
- Hesap başvurusu/onay akışı dokümantasyonu
- Firestore ve Storage güvenlik kuralları taslağı
- Kullanıcı başvurusu ve yönetici onayına yönelik Cloud Functions başlangıcı
- İlk yönetici kullanıcı adı: `avcanadmin` (parola oluşturulmadı)

## Giriş modeli
Personel şube kodu + kullanıcı adı + parola ile giriş yapacak. Firebase Authentication e-posta/parola için dahili kimlik biçimi `şubeKodu.kullanıcıAdı@users.avcan.local` olarak tasarlandı. Aynı kullanıcı adı farklı şubelerde kullanılabilir; aynı şubede benzersiz olmalıdır. Bu e-posta yalnızca teknik kimliktir.

## Canlı kurulum için gerekenler
1. Kurum adına Firebase projesi açılması.
2. Android uygulamasının Firebase'e eklenmesi ve `google-services.json` dosyasının `app/` klasörüne konması.
3. Authentication içinde Email/Password yönteminin açılması.
4. Firestore ve Storage oluşturulması.
5. `firebase login`, ardından `firebase deploy --only firestore:rules,storage`.
6. `functions/` içinde `npm install`, `npm run build`, sonra Cloud Functions dağıtımı.
7. `avcanadmin` ilk hesabının kontrollü tek seferlik kurulumla oluşturulması; `owner_admin` rolü yalnızca güvenilir Admin SDK üzerinden atanmalı.
8. Android'e Firebase Auth, Firestore, Storage, Functions ve FCM SDK'larının bağlanması.
9. XLSX ayrıştırma, satır doğrulama, şube eşleştirme ve fiyat değişikliklerini dağıtma tamamlanmalı.
10. Fotoğraf kanıtı, FCM, işlem zamanları ve gerçek cihaz testleri yapılmalı.

## Güvenlik
- Yönetici rolü mobil kayıt ekranından verilemez.
- Personel erişimi Firestore kurallarıyla şubeye göre sınırlandırılır.
- Bu backend taslağında XLSX ayrıştırma ve FCM dağıtımı henüz yoktur. `createImportRecord` yalnızca aktarım metaverisi kaydeder; canlı fiyat güncellemesi yapmaz.
- Etiket tamamlandı durumu, fotoğraf yüklemesi başarılı olmadan verilememelidir.

## İlk yönetici hesabını kurma (güvenilir makinede)

` scripts/bootstrap-admin.mjs ` dosyası ilk yönetici ve altı şube kaydını Admin SDK ile oluşturur. Önce Firebase projesinde Firestore/Auth açılmalı ve makinede Application Default Credentials yetkisi tanımlanmalıdır. Ardından `GCLOUD_PROJECT` ve `ADMIN_PASSWORD` ortam değişkenlerini vererek script çalıştırılır. Parola en az 12 karakter olmalı; kaynak dosyaya yazılmamalı. Bu işlem uygulama içinden herkese açık şekilde çalıştırılmamalıdır.
