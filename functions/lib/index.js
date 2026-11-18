"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.importPriceChanges = exports.createImportRecord = exports.rejectStaff = exports.approveStaff = exports.registerStaffProfile = void 0;
const https_1 = require("firebase-functions/v2/https");
const app_1 = require("firebase-admin/app");
const auth_1 = require("firebase-admin/auth");
const firestore_1 = require("firebase-admin/firestore");
(0, app_1.initializeApp)();
const db = (0, firestore_1.getFirestore)();
const BRANCH_NAMES = {
    "10": "Yaylapınar",
    "20": "Ankara Yolu",
    "40": "Aydınlık",
    "41": "Fetih",
    "95": "Aydınlık AVM",
    "96": "Fetih AVM",
};
// Eski personel kayıt fonksiyonuyla uyumluluk için aynı şube sözlüğü.
const BRANCHES = BRANCH_NAMES;
function cleanUsername(value) {
    if (typeof value !== "string") {
        throw new https_1.HttpsError("invalid-argument", "Kullanıcı adı gerekli.");
    }
    const username = value.trim().toLowerCase();
    if (!/^[a-z0-9._-]{3,24}$/.test(username)) {
        throw new https_1.HttpsError("invalid-argument", "Kullanıcı adı 3-24 karakter olmalı. Yalnızca a-z, 0-9, nokta, tire ve alt çizgi kullanılabilir.");
    }
    return username;
}
function syntheticEmail(branchCode, username) {
    return `${branchCode}.${username}@users.avcan.local`;
}
/** Approved admin account check shared by all admin-only callables. */
async function requireAdmin(uid) {
    if (!uid)
        throw new https_1.HttpsError("unauthenticated", "Giriş yapmalısınız.");
    const snap = await db.doc(`users/${uid}`).get();
    const d = snap.data();
    if (!d ||
        !["admin", "owner_admin"].includes(String(d.role)) ||
        d.approvalStatus !== "approved") {
        throw new https_1.HttpsError("permission-denied", "Yönetici yetkisi gerekli.");
    }
    return d;
}
// Kullanıcı önce Firebase Authentication'da şube.kullaniciadi@users.avcan.local
// adresiyle hesabını oluşturur; ardından bu callable profilini beklemeye alır.
exports.registerStaffProfile = (0, https_1.onCall)(async (request) => {
    const uid = request.auth?.uid;
    if (!uid) {
        throw new https_1.HttpsError("unauthenticated", "Oturum açılmalı.");
    }
    const fullName = String(request.data?.fullName ?? "").trim();
    const branchCode = String(request.data?.branchCode ?? "").trim();
    const username = cleanUsername(request.data?.username);
    if (fullName.length < 3 || fullName.length > 80 || !BRANCHES[branchCode]) {
        throw new https_1.HttpsError("invalid-argument", "Ad soyad veya şube geçersiz.");
    }
    const authUser = await (0, auth_1.getAuth)().getUser(uid);
    if (authUser.email !== syntheticEmail(branchCode, username)) {
        throw new https_1.HttpsError("permission-denied", "Hesap bilgileri eşleşmiyor.");
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
            throw new https_1.HttpsError("already-exists", "Bu şubede kullanıcı adı kullanımda.");
        }
        if (userSnapshot.exists) {
            throw new https_1.HttpsError("already-exists", "Bu hesap için başvuru zaten var.");
        }
        transaction.create(usernameRef, {
            uid,
            branchCode,
            username,
            createdAt: firestore_1.FieldValue.serverTimestamp(),
        });
        transaction.create(userRef, {
            fullName,
            username,
            branchCode,
            role: "staff",
            approvalStatus: "pending",
            createdAt: firestore_1.FieldValue.serverTimestamp(),
            createdBy: uid,
        });
    });
    return {
        status: "pending",
        message: "Başvurunuz yönetici onayına gönderildi.",
    };
});
exports.approveStaff = (0, https_1.onCall)(async (request) => {
    const admin = await requireAdmin(request.auth?.uid);
    const uid = String(request.data?.uid ?? "").trim();
    const branchCode = String(request.data?.branchCode ?? "").trim();
    if (!uid || !BRANCHES[branchCode]) {
        throw new https_1.HttpsError("invalid-argument", "Kullanıcı veya şube geçersiz.");
    }
    const userRef = db.doc(`users/${uid}`);
    const userSnapshot = await userRef.get();
    if (!userSnapshot.exists || userSnapshot.data()?.approvalStatus !== "pending") {
        throw new https_1.HttpsError("failed-precondition", "Onay bekleyen kullanıcı bulunamadı.");
    }
    await userRef.update({
        branchCode,
        role: "staff",
        approvalStatus: "approved",
        approvedBy: request.auth.uid,
        approvedByName: admin.fullName ?? "Yönetici",
        approvedAt: firestore_1.FieldValue.serverTimestamp(),
    });
    return { ok: true };
});
exports.rejectStaff = (0, https_1.onCall)(async (request) => {
    await requireAdmin(request.auth?.uid);
    const uid = String(request.data?.uid ?? "").trim();
    if (!uid) {
        throw new https_1.HttpsError("invalid-argument", "Kullanıcı gerekli.");
    }
    const userRef = db.doc(`users/${uid}`);
    const userSnapshot = await userRef.get();
    if (!userSnapshot.exists || userSnapshot.data()?.approvalStatus !== "pending") {
        throw new https_1.HttpsError("failed-precondition", "Başvuru bulunamadı.");
    }
    // Kullanıcı adı kaydı bilerek korunur; reddedilen başvurunun aynı kullanıcı
    // adıyla tekrar açılması için yönetici tarafından ayrıca işlem yapılmalıdır.
    await userRef.update({
        approvalStatus: "rejected",
        rejectedBy: request.auth.uid,
        rejectedAt: firestore_1.FieldValue.serverTimestamp(),
    });
    return { ok: true };
});
// Eski panel akışıyla uyumlu metadata kaydı. Bu fonksiyon tek başına Excel
// satırlarını içe aktarmaz; gerçek aktarım importPriceChanges üzerinden yapılır.
exports.createImportRecord = (0, https_1.onCall)(async (request) => {
    const admin = await requireAdmin(request.auth?.uid);
    const fileName = String(request.data?.fileName ?? "").trim();
    const rowCount = Number(request.data?.rowCount ?? 0);
    if (!fileName || !Number.isInteger(rowCount) || rowCount < 0) {
        throw new https_1.HttpsError("invalid-argument", "Dosya adı veya satır sayısı geçersiz.");
    }
    const importRef = await db.collection("imports").add({
        uploadedAt: firestore_1.FieldValue.serverTimestamp(),
        uploadedBy: request.auth.uid,
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
exports.importPriceChanges = (0, https_1.onCall)({ region: "europe-west1", timeoutSeconds: 300, memory: "512MiB" }, async (request) => {
    const uid = request.auth?.uid;
    const admin = await requireAdmin(uid);
    const rowsInput = request.data?.rows;
    const modeValue = String(request.data?.mode ?? "excel");
    const mode = modeValue;
    const selectedBranches = Array.isArray(request.data?.targetBranches)
        ? Array.from(new Set(request.data.targetBranches.map((value) => String(value).trim())))
        : [];
    if (!Array.isArray(rowsInput) || rowsInput.length < 1 || rowsInput.length > 1000) {
        throw new https_1.HttpsError("invalid-argument", "Excel 1-1000 geçerli satır içermelidir.");
    }
    if (!["excel", "manual", "combined"].includes(modeValue)) {
        throw new https_1.HttpsError("invalid-argument", "Dağıtım yöntemi geçersiz.");
    }
    if (mode !== "excel" &&
        (selectedBranches.length === 0 || selectedBranches.some((branch) => !BRANCH_NAMES[branch]))) {
        throw new https_1.HttpsError("invalid-argument", "En az bir geçerli hedef şube seçin.");
    }
    const seen = new Set();
    const normalized = rowsInput.map((raw, index) => {
        const barcode = String(raw?.barcode ?? "").trim();
        const productName = String(raw?.productName ?? "").trim();
        const oldPriceRaw = raw?.oldPrice;
        const newPriceRaw = raw?.newPrice;
        const oldPrice = Number(oldPriceRaw);
        const newPrice = Number(newPriceRaw);
        const excelBranch = String(raw?.branchCode ?? "").trim();
        if (!barcode || barcode.length > 64 ||
            !productName || productName.length > 200 ||
            oldPriceRaw === undefined || oldPriceRaw === null || String(oldPriceRaw).trim() === "" ||
            newPriceRaw === undefined || newPriceRaw === null || String(newPriceRaw).trim() === "" ||
            !Number.isFinite(oldPrice) || !Number.isFinite(newPrice) ||
            oldPrice < 0 || newPrice < 0) {
            throw new https_1.HttpsError("invalid-argument", `${index + 2}. satırda barkod, ürün adı veya fiyat hatalı.`);
        }
        if (mode !== "manual" && !BRANCH_NAMES[excelBranch]) {
            throw new https_1.HttpsError("invalid-argument", `${index + 2}. satırda şube kodu geçersiz.`);
        }
        const targets = mode === "excel"
            ? [excelBranch]
            : mode === "manual"
                ? selectedBranches
                : [excelBranch, ...selectedBranches];
        const uniqueTargets = Array.from(new Set(targets));
        for (const branch of uniqueTargets) {
            if (!BRANCH_NAMES[branch]) {
                throw new https_1.HttpsError("invalid-argument", `${index + 2}. satırda hedef şube geçersiz.`);
            }
            const duplicateKey = `${branch}:${barcode}`;
            if (seen.has(duplicateKey)) {
                throw new https_1.HttpsError("already-exists", `Tekrarlanan barkod/şube: ${barcode} / ${BRANCH_NAMES[branch]}`);
            }
            seen.add(duplicateKey);
        }
        return { barcode, productName, oldPrice, newPrice, targets: uniqueTargets };
    });
    const totalRecords = normalized.reduce((sum, row) => sum + row.targets.length, 0);
    if (totalRecords > 4000) {
        throw new https_1.HttpsError("invalid-argument", "Şubelere dağıtılan toplam kayıt 4000'i aşamaz.");
    }
    const importRef = db.collection("imports").doc();
    await importRef.set({
        uploadedAt: firestore_1.FieldValue.serverTimestamp(),
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
                    createdAt: firestore_1.FieldValue.serverTimestamp(),
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
            if (pendingSnapshot.empty)
                break;
            const publishBatch = db.batch();
            pendingSnapshot.docs.forEach((doc) => {
                publishBatch.update(doc.ref, { published: true });
            });
            await publishBatch.commit();
        }
        // 3. Aktarımı tamamlandı olarak işaretle.
        await importRef.update({
            status: "completed",
            completedAt: firestore_1.FieldValue.serverTimestamp(),
            createdRecordCount: createdCount,
        });
        return {
            importId: importRef.id,
            sourceRows: normalized.length,
            createdRecords: createdCount,
            status: "completed",
        };
    }
    catch (error) {
        // 4. Hata olursa bu aktarıma ait bütün kayıtları temizle.
        while (true) {
            const failedSnapshot = await db.collection("priceChanges")
                .where("importId", "==", importRef.id)
                .limit(450)
                .get();
            if (failedSnapshot.empty)
                break;
            const deleteBatch = db.batch();
            failedSnapshot.docs.forEach((doc) => {
                deleteBatch.delete(doc.ref);
            });
            await deleteBatch.commit();
        }
        await importRef.update({
            status: "failed",
            errorMessage: String(error).slice(0, 500),
            failedAt: firestore_1.FieldValue.serverTimestamp(),
        });
        throw new https_1.HttpsError("internal", "Aktarım tamamlanamadı. İçe aktarma geçmişini kontrol edin.");
    }
});
