# Hesap açma ve onay akışı

1. Personel uygulamada "Hesap oluştur" seçer.
2. Ad-soyad, atanacağı şube, kullanıcı adı ve parola girer.
3. Başvuru `pending` durumunda kaydedilir; kullanıcıya "Yönetici onayı bekleniyor" gösterilir.
4. Yönetici panelinde başvuru; ad-soyad, kullanıcı adı, seçilen şube ve başvuru zamanı ile görünür.
5. Yönetici onaylar veya reddeder. Onay sırasında rolü `staff` olarak atar ve şubeyi doğrular/değiştirir.
6. Onaylanan kullanıcı şube + kullanıcı adı + parola ile giriş yapar.
7. Yönetici hesabı normal kayıt formundan üretilemez. İlk yönetici kurum sahibi için kontrollü kurulum/davet yoluyla oluşturulur.

## Giriş alanları

- Şube/lokasyon: altı şubeden seçim
- Kullanıcı adı
- Parola

Şube seçimi bir erişim/hesap eşleştirme alanıdır; telefon GPS konumu olarak yorumlanmaz. Kullanıcı farklı şube seçerek başka şubenin verisine erişemez; sunucu tarafı yetki kontrolü esas alınır.
