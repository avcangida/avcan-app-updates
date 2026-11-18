import { onCall, HttpsError } from "firebase-functions/v2/https";
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, FieldValue } from "firebase-admin/firestore";

initializeApp();
const db = getFirestore();

const BRANCH_NAMES: Record<string, string> = {
  "10": "Yaylapınar",
  "20": "Ankara Yolu",
  "40": "Aydınlık",
  "41": "Fetih",
  "95": "Aydınlık AVM",
  "96": "Fetih AVM",
};

// Eski personel kayıt fonksiyonuyla uyumluluk için aynı şube sözlüğü.
const BRANCHES = BRANCH_NAMES;

type ImportMode = "excel" | "manual" | "combined";

interface ImportRowInput {
  barcode?: unknown;
  productName?: unknown;
  oldPrice?: unknown;
  newPrice?: unknown;
  branchCode?: unknown;
}

interface NormalizedImportRow {
  barcode: string;
  productName: string;
  oldPrice: number;
  newPrice: number;
  targets: string[];
}

function cleanUsername(value: unknown): string {
  if (typeof value !== "string") {
    throw new HttpsError("invalid-argument", "Kullanıcı adı gerekli.");
  }

  const username = value.trim().toLowerCase();
  if (!/^[a-z0-9._-]{3,24}$/.test(username)) {
    throw new HttpsError(
      "invalid-argument",
      "Kullanıcı adı 3-24 karakter olmalı. Yalnızca a-z, 0-9, nokta, tire ve alt çizgi kullanılabilir."
    );
  }
  return username;
}

function syntheticEmail(branchCode: string, username: string): string {
  return `${branchCode}.${username}@users.avcan.local`;
}

/** Approved admin account check shared by all admin-only callables. */
async function requireAdmin(uid?: string) {
  if (!uid) throw new HttpsError("unauthenticated","Giriş yapmalısınız.");
  const snap = await db.doc(`users/${uid}`).get();
  const d = snap.data();

  if (
    !d ||
    !["admin", "owner_admin"].includes(String(d.role)) ||
    d.approvalStatus !== "approved"
  ) {
    throw new HttpsError("permission-denied","Yönetici yetkisi gerekli.");
  }

  return d;
}

// Kullanıcı önce Firebase Authentication'da şube.kullaniciadi@users.avcan.local
// adresiyle hesabını oluşturur; ardından bu callable profilini beklemeye alır.
export const registerStaffProfile = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) {
    throw new HttpsError("unauthenticated", "Oturum açılmalı.");
  }

  const fullName = String(request.data?.fullName ?? "").trim();
  const branchCode = String(request.data?.branchCode ?? "").trim();
  const username = cleanUsername(request.data?.username);

  if (fullName.length < 3 || fullName.length > 80 || !BRANCHES[branchCode]) {
    throw new HttpsError("invalid-argument", "Ad soyad veya şube geçersiz.");
  }

  const authUser = await getAuth().getUser(uid);
  if (authUser.email !== syntheticEmail(branchCode, username)) {
    throw new HttpsError("permission-denied", "Hesap bilgileri eşleşmiyor.");
  }

  const usernameKey = `${branchCode}_${username}`;
  await db.runTransaction(async (transaction) => {
    const usernameRef = db.doc(`usernames/${usernameKey}`);
    const userRef = db.doc(`users/${uid}`);
    const [usernameSnapshot, userSnapshot] = await Promise.all([
      transaction.get(usernameRef),
      transaction.get(userRef),
    ]);

    if (usernameSnapshot.exists && usernameSnapshot.data()?.uid !== uid) {
      throw new HttpsError("already-exists", "Bu şubede kullanıcı adı kullanımda.");
    }
    if (userSnapshot.exists) {
      throw new HttpsError("already-exists", "Bu hesap için başvuru zaten var.");
    }

    transaction.create(usernameRef, {
      uid,
      branchCode,
      username,
      createdAt: FieldValue.serverTimestamp(),
    });
    transaction.create(userRef, {
      fullName,
      username,
      branchCode,
      role: "staff",
      approvalStatus: "pending",
      createdAt: FieldValue.serverTimestamp(),
      createdBy: uid,
    });
  });

  return {
    status: "pending",
    message: "Başvurunuz yönetici onayına gönderildi.",
  };
});

export const approveStaff = onCall(async (request) => {
  const admin = await requireAdmin(request.auth?.uid);
  const uid = String(request.data?.uid ?? "").trim();
  const branchCode = String(request.data?.branchCode ?? "").trim();

  if (!uid || !BRANCHES[branchCode]) {
    throw new HttpsError("invalid-argument", "Kullanıcı veya şube geçersiz.");
  }

  const userRef = db.doc(`users/${uid}`);
  const userSnapshot = await userRef.get();
  if (!userSnapshot.exists || userSnapshot.data()?.approvalStatus !== "pending") {
    throw new HttpsError("failed-precondition", "Onay bekleyen kullanıcı bulunamadı.");
  }

  await userRef.update({
    branchCode,
    role: "staff",
    approvalStatus: "approved",
    approvedBy: request.auth!.uid,
    approvedByName: admin.fullName ?? "Yönetici",
    approvedAt: FieldValue.serverTimestamp(),
  });

  return { ok: true };
});

export const rejectStaff = onCall(async (request) => {
  await requireAdmin(request.auth?.uid);
  const uid = String(request.data?.uid ?? "").trim();
  if (!uid) {
    throw new HttpsError("invalid-argument", "Kullanıcı gerekli.");
  }

  const userRef = db.doc(`users/${uid}`);
  const userSnapshot = await userRef.get();
  if (!userSnapshot.exists || userSnapshot.data()?.approvalStatus !== "pending") {
    throw new HttpsError("failed-precondition", "Başvuru bulunamadı.");
  }

  // Kullanıcı adı kaydı bilerek korunur; reddedilen başvurunun aynı kullanıcı
  // adıyla tekrar açılması için yönetici tarafından ayrıca işlem yapılmalıdır.
  await userRef.update({
    approvalStatus: "rejected",
    rejectedBy: request.auth!.uid,
    rejectedAt: FieldValue.serverTimestamp(),
  });

  return { ok: true };
});

// Eski panel akışıyla uyumlu metadata kaydı. Bu fonksiyon tek başına Excel
// satırlarını içe aktarmaz; gerçek aktarım importPriceChanges üzerinden yapılır.
export const createImportRecord = onCall(async (request) => {
  const admin = await requireAdmin(request.auth?.uid);
  const fileName = String(request.data?.fileName ?? "").trim();
  const rowCount = Number(request.data?.rowCount ?? 0);

  if (!fileName || !Number.isInteger(rowCount) || rowCount < 0) {
    throw new HttpsError("invalid-argument", "Dosya adı veya satır sayısı geçersiz.");
  }

  const importRef = await db.collection("imports").add({
    uploadedAt: FieldValue.serverTimestamp(),
    uploadedBy: request.auth!.uid,
    uploadedByName: admin.fullName ?? "Yönetici",
    fileName: fileName.slice(0, 180),
    rowCount,
    status: "received",
  });

  return { importId: importRef.id, status: "received" };
});

/**
 * Excel satırlarını doğrular ve hedef şubelere Firestore kayıtları oluşturur.
 * Tarayıcı tarafı XLSX dosyasını rows dizisine dönüştürüp bu callable'ı çağırmalıdır.
 */
export const importPriceChanges = onCall(
  { region: "europe-west1", timeoutSeconds: 300, memory: "512MiB" },
  async (request) => {
    const uid = request.auth?.uid;
    const admin = await requireAdmin(uid);
    const rowsInput: unknown = request.data?.rows;
    const modeValue = String(request.data?.mode ?? "excel");
    const mode = modeValue as ImportMode;

    const selectedBranches: string[] = Array.isArray(request.data?.targetBranches)
      ? Array.from(new Set<string>(
        (request.data.targetBranches as unknown[]).map((value) => String(value).trim())
      ))
      : [];

    if (!Array.isArray(rowsInput) || rowsInput.length < 1 || rowsInput.length > 1000) {
      throw new HttpsError("invalid-argument", "Excel 1-1000 geçerli satır içermelidir.");
    }
    if (!["excel", "manual", "combined"].includes(modeValue)) {
      throw new HttpsError("invalid-argument", "Dağıtım yöntemi geçersiz.");
    }
    if (
      mode !== "excel" &&
      (selectedBranches.length === 0 || selectedBranches.some((branch) => !BRANCH_NAMES[branch]))
    ) {
      throw new HttpsError("invalid-argument", "En az bir geçerli hedef şube seçin.");
    }

    const seen = new Set<string>();
    const normalized: NormalizedImportRow[] = (rowsInput as ImportRowInput[]).map(
      (raw, index) => {
        const barcode = String(raw?.barcode ?? "").trim();
        const productName = String(raw?.productName ?? "").trim();
        const oldPriceRaw = raw?.oldPrice;
        const newPriceRaw = raw?.newPrice;
        const oldPrice = Number(oldPriceRaw);
        const newPrice = Number(newPriceRaw);
        const excelBranch = String(raw?.branchCode ?? "").trim();

        if (
          !barcode || barcode.length > 64 ||
          !productName || productName.length > 200 ||
          oldPriceRaw === undefined || oldPriceRaw === null || String(oldPriceRaw).trim() === "" ||
          newPriceRaw === undefined || newPriceRaw === null || String(newPriceRaw).trim() === "" ||
          !Number.isFinite(oldPrice) || !Number.isFinite(newPrice) ||
          oldPrice < 0 || newPrice < 0
        ) {
          throw new HttpsError(
            "invalid-argument",
            `${index + 2}. satırda barkod, ürün adı veya fiyat hatalı.`
          );
        }

        if (mode !== "manual" && !BRANCH_NAMES[excelBranch]) {
          throw new HttpsError("invalid-argument", `${index + 2}. satırda şube kodu geçersiz.`);
        }

        const targets: string[] = mode === "excel"
          ? [excelBranch]
          : mode === "manual"
            ? selectedBranches
            : [excelBranch, ...selectedBranches];
        const uniqueTargets = Array.from(new Set<string>(targets));

        for (const branch of uniqueTargets) {
          if (!BRANCH_NAMES[branch]) {
            throw new HttpsError("invalid-argument", `${index + 2}. satırda hedef şube geçersiz.`);
          }
          const duplicateKey = `${branch}:${barcode}`;
          if (seen.has(duplicateKey)) {
            throw new HttpsError(
              "already-exists",
              `Tekrarlanan barkod/şube: ${barcode} / ${BRANCH_NAMES[branch]}`
            );
          }
          seen.add(duplicateKey);
        }

        return { barcode, productName, oldPrice, newPrice, targets: uniqueTargets };
      }
    );

    const totalRecords = normalized.reduce((sum, row) => sum + row.targets.length, 0);
    if (totalRecords > 4000) {
      throw new HttpsError("invalid-argument", "Şubelere dağıtılan toplam kayıt 4000'i aşamaz.");
    }

    const importRef = db.collection("imports").doc();
    await importRef.set({
      uploadedAt: FieldValue.serverTimestamp(),
      uploadedBy: uid,
      uploadedByName: admin.fullName ?? "Yönetici",
      fileName: String(request.data?.fileName ?? "fiyat-listesi.xlsx").slice(0, 180),
      sourceRowCount: normalized.length,
      rowCount: totalRecords,
      routingMode: mode,
      targetBranches: selectedBranches,
      status: "processing",
    });


    try {
      let batch = db.batch();
      let batchCount = 0;
      let createdCount = 0;

      // 1. Önce bütün kayıtları gizli olarak oluştur.
      for (const row of normalized) {
        for (const branchCode of row.targets) {
          const priceChangeRef = db.collection("priceChanges").doc();

          batch.set(priceChangeRef, {
            importId: importRef.id,
            branchCode,
            branchName: BRANCH_NAMES[branchCode],
            barcode: row.barcode,
            productName: row.productName,
            oldPrice: row.oldPrice,
            newPrice: row.newPrice,
            createdAt: FieldValue.serverTimestamp(),
            createdBy: uid,
            status: "pending",
            published: false,
          });

          batchCount++;
          createdCount++;

          if (batchCount === 450) {
            await batch.commit();
            batch = db.batch();
            batchCount = 0;
          }
        }
      }

      if (batchCount > 0) {
        await batch.commit();
      }

      // 2. Bütün kayıtlar oluşturulduktan sonra yayımla.
      while (true) {
        const pendingSnapshot = await db.collection("priceChanges")
          .where("importId", "==", importRef.id)
          .where("published", "==", false)
          .limit(450)
          .get();

        if (pendingSnapshot.empty) break;

        const publishBatch = db.batch();

        pendingSnapshot.docs.forEach((doc) => {
          publishBatch.update(doc.ref, { published: true });
        });

        await publishBatch.commit();
      }

      // 3. Aktarımı tamamlandı olarak işaretle.
      await importRef.update({
        status: "completed",
        completedAt: FieldValue.serverTimestamp(),
        createdRecordCount: createdCount,
      });

      return {
        importId: importRef.id,
        sourceRows: normalized.length,
        createdRecords: createdCount,
        status: "completed",
      };
    } catch (error) {
      // 4. Hata olursa bu aktarıma ait bütün kayıtları temizle.
      while (true) {
        const failedSnapshot = await db.collection("priceChanges")
          .where("importId", "==", importRef.id)
          .limit(450)
          .get();

        if (failedSnapshot.empty) break;

        const deleteBatch = db.batch();

        failedSnapshot.docs.forEach((doc) => {
          deleteBatch.delete(doc.ref);
        });

        await deleteBatch.commit();
      }

      await importRef.update({
        status: "failed",
        errorMessage: String(error).slice(0, 500),
        failedAt: FieldValue.serverTimestamp(),
      });

      throw new HttpsError(
        "internal",
        "Aktarım tamamlanamadı. İçe aktarma geçmişini kontrol edin."
      );
    }

  }
);