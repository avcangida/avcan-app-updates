// Run from a trusted machine after `gcloud auth application-default login`.
// Never commit the password or service-account credentials.
import { initializeApp, applicationDefault } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { getFirestore, FieldValue } from 'firebase-admin/firestore';

const projectId = process.env.GCLOUD_PROJECT;
const password = process.env.ADMIN_PASSWORD;
if (!projectId || !password || password.length < 12) {
  throw new Error('Set GCLOUD_PROJECT and ADMIN_PASSWORD (minimum 12 characters) before running.');
}
initializeApp({ credential: applicationDefault(), projectId });
const auth = getAuth();
const db = getFirestore();
const username = 'avcanadmin';
const email = 'all.avcanadmin@users.avcan.local';
let user;
try { user = await auth.getUserByEmail(email); }
catch { user = await auth.createUser({ email, password, displayName: 'AVCAN GIDA Yönetici', disabled: false }); }
await auth.updateUser(user.uid, { password, disabled: false, emailVerified: true });
await db.doc(`users/${user.uid}`).set({
  fullName: 'AVCAN GIDA Yönetici', username, branchCode: 'ALL',
  role: 'owner_admin', approvalStatus: 'approved',
  createdAt: FieldValue.serverTimestamp(), approvedAt: FieldValue.serverTimestamp(),
  bootstrapAccount: true
}, { merge: true });
await db.doc('usernames/ALL_avcanadmin').set({ uid: user.uid, branchCode: 'ALL', username }, { merge: true });
const branches = {
  '10': 'Yaylapınar', '20': 'Ankara Yolu', '40': 'Aydınlık',
  '41': 'Fetih', '95': 'Aydınlık AVM', '96': 'Fetih AVM'
};
const batch = db.batch();
for (const [code, name] of Object.entries(branches)) {
  batch.set(db.doc(`branches/${code}`), { code, name, active: true }, { merge: true });
}
await batch.commit();
console.log(`Admin initialized: ${username} (${user.uid}). Password was not printed.`);
