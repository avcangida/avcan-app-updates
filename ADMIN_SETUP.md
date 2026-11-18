# İlk yönetici hesabı – AVCAN GIDA

## Ayrılmış yönetici kullanıcı adı

- Kullanıcı adı: `avcanadmin`
- Rol: `owner_admin`
- Erişim: Altı şubenin tamamı
- Yetkiler: Kullanıcı başvurularını onaylama/reddetme, personelin şubesini ve rolünü atama, Excel fiyat listesi yükleme, raporları görüntüleme.

## Güvenli ilk kurulum

Bu kaynak paketinde gerçek bir yönetici hesabı veya parola oluşturulmuş değildir. Henüz kimlik doğrulama sunucusu/Firebase projesi bağlanmadığı için yerel bir kullanıcıyı gerçek hesap gibi göstermek güvenli olmaz.

Canlı kurulumda ilk açılışta `avcanadmin` kullanıcı adıyla tek seferlik yönetici oluşturma adımı gösterilmelidir. Kurum sahibi parolayı bu kurulum ekranında kendisi belirler. Parola Firebase Authentication tarafından yönetilir; kaynak koda, ZIP dosyasına veya uygulama içine sabit yazılmaz.

İlk yönetici oluşturulduktan sonra ilk-kurulum bağlantısı kapatılmalı; uygulamadaki normal kayıt ekranından yönetici rolü seçilememelidir.

## Giriş biçimi

- Şube/lokasyon: 10, 20, 40, 41, 95, 96 (yönetici için “Tüm şubeler”)
- Kullanıcı adı
- Parola

Personel başvuruları `pending` durumunda kalır. Yönetici onaylamadan giriş yapamaz. Onay sırasında şube ve rol atanır.

## Canlıya geçişten önce gerekli işler

1. Firebase projesi oluşturulup Android uygulamasına bağlanmalı.
2. Firebase Authentication kullanıcı adı/parola eşlemesi için güvenli sunucu fonksiyonu kurulmalı.
3. Firestore güvenlik kurallarıyla personelin yalnızca atandığı şubeye erişmesi zorlanmalı.
4. İlk yönetici tek seferlik güvenli kurulumla oluşturulmalı.
5. Kayıt/onay, Excel aktarımı, fotoğraf yükleme ve FCM bildirimleri gerçek cihazlarda test edilmeli.
