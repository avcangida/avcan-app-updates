# Firebase yapılandırma dosyası

`app/google-services.json` dosyası, Firebase projesinden indirilen Android yapılandırma dosyasıdır.

Doğrulanan eşleşmeler:
- Firebase project ID: `avcan-gida-fiyat-takip`
- Android package name: `com.avcan.fiyatetiket`

Bu dosyanın eklenmesi tek başına Firebase SDK entegrasyonunu tamamlamaz. Google Services Gradle eklentisi, Firebase bağımlılıkları, Authentication, Firestore, Storage kuralları ve sunucu fonksiyonları ayrıca yapılandırılıp test edilmelidir.


## Gradle entegrasyonu
- Google Services Gradle plugin: `com.google.gms.google-services` 4.6.0
- Firebase Android BoM: 35.2.0
- Auth, Firestore, Storage, Messaging ve Functions bağımlılıkları eklendi.
- Bu, SDK/Gradle yapılandırmasını hazırlar; Firebase Console servislerini açmak, veritabanı/bucket kurmak, güvenlik kurallarını dağıtmak ve uygulama kodunu gerçek servislere bağlayıp test etmek ayrıca gerekir.
