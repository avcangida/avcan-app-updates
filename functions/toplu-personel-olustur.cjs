/**
 * AVCAN GIDA - Toplu personel oluşturma
 * Bu dosyayı avcan-fiyat-etiket1/functions klasörüne koyun.
 * Firebase Admin SDK ile yerel bilgisayarda çalışır; Cloud Functions deploy etmez.
 * Servis hesabı anahtarını kimseyle paylaşmayın ve Git'e eklemeyin.
 */

const admin = require('firebase-admin');
const fs = require('fs');
const path = require('path');

const KEY_PATH = path.join(__dirname, 'serviceAccountKey.json');
const PROJECT_ID = 'avcan-gida-fiyat-takip';
const SHARED_PASSWORD = 'AVC142536';

const staff = [
  { fullName: 'Hasan Kurani', username: 'hasan10', branchCode: '10' },
  { fullName: 'Gökhan Gök', username: 'gokhan20', branchCode: '20' },
  { fullName: 'Celalettin Göçü', username: 'celal20', branchCode: '20' },
  { fullName: 'H. Hüseyin Selek', username: 'huseyin40', branchCode: '40' },
  { fullName: 'Ahmet Sümen', username: 'ahmet40', branchCode: '40' },
  { fullName: 'Fatih Kuyucu', username: 'fatih41', branchCode: '41' },
  { fullName: 'Bayram Nar', username: 'bayram41', branchCode: '41' },
  { fullName: 'Yasin Akkaya', username: 'yasin95', branchCode: '95' },
  { fullName: 'Murat', username: 'murat95', branchCode: '95' },
  { fullName: 'Mustafa Nar', username: 'mustafa96', branchCode: '96' },
  { fullName: 'Fatih', username: 'fatih96', branchCode: '96' },
];

async function main() {
  if (!fs.existsSync(KEY_PATH)) {
    throw new Error(
      'serviceAccountKey.json bulunamadı. Firebase Console > Project settings > Service accounts bölümünden anahtarı indirip functions klasörüne koyun.'
    );
  }

  const serviceAccount = JSON.parse(fs.readFileSync(KEY_PATH, 'utf8'));
  if (serviceAccount.project_id !== PROJECT_ID) {
    throw new Error(`Yanlış Firebase projesi. Beklenen: ${PROJECT_ID}; dosyadaki: ${serviceAccount.project_id}`);
  }

  admin.initializeApp({
    credential: admin.credential.cert(serviceAccount),
    projectId: PROJECT_ID,
  });

  const auth = admin.auth();
  const db = admin.firestore();

  // Önce tüm şubelerin var ve aktif olduğunu doğrula; eksik varsa hiçbir hesap açma.
  const branchCodes = [...new Set(staff.map((person) => person.branchCode))];
  for (const code of branchCodes) {
    const branchSnap = await db.collection('branches').doc(code).get();
    if (!branchSnap.exists || branchSnap.get('active') !== true || !branchSnap.get('name')) {
      throw new Error(`${code} kodlu şube bulunamadı, pasif veya adı eksik. İşlem durduruldu.`);
    }
  }

  console.log(`Toplam ${staff.length} personel işlenecek. Proje: ${PROJECT_ID}`);

  let created = 0;
  let existing = 0;
  const failures = [];

  for (const person of staff) {
    const email = `${person.branchCode}.${person.username}@users.avcan.local`;
    try {
      let userRecord;
      let isNewUser = false;
      try {
        userRecord = await auth.createUser({
          email,
          password: SHARED_PASSWORD,
          displayName: person.fullName,
          disabled: false,
        });
        created++;
        isNewUser = true;
      } catch (error) {
        if (error.code !== 'auth/email-already-exists') throw error;
        userRecord = await auth.getUserByEmail(email);
        existing++;
        await auth.updateUser(userRecord.uid, {
          displayName: person.fullName,
          disabled: false,
        });
      }

      await db.collection('users').doc(userRecord.uid).set(
        {
          fullName: person.fullName,
          username: person.username,
          branchCode: person.branchCode,
          role: 'staff',
          approvalStatus: 'approved',
          email,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          ...(isNewUser ? { createdAt: admin.firestore.FieldValue.serverTimestamp() } : {}),
        },
        { merge: true }
      );

      console.log(`OK  ${person.fullName} | ${person.username} | Şube ${person.branchCode} | UID ${userRecord.uid}`);
    } catch (error) {
      failures.push({ person: person.fullName, email, error: error.message });
      console.error(`HATA ${person.fullName} (${email}): ${error.message}`);
    }
  }

  console.log('\nİŞLEM ÖZETİ');
  console.log(`Yeni Authentication hesabı: ${created}`);
  console.log(`Önceden mevcut hesap: ${existing}`);
  console.log(`Hata: ${failures.length}`);
  console.log('Mevcut hesaplar bulunursa aynı UID altında profil bilgileri güncellenir.');

  if (failures.length) {
    console.log('\nBaşarısız kayıtlar:');
    for (const item of failures) console.log(`- ${item.person}: ${item.error}`);
    process.exitCode = 1;
  } else {
    console.log('\nTamamlandı. Android uygulamasında kullanıcı adı ve ortak şifreyle giriş yapılabilir.');
  }
}

main().catch((error) => {
  console.error('\nİŞLEM DURDURULDU:', error.message);
  process.exitCode = 1;
});
