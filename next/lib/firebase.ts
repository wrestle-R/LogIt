import { getApp, getApps, initializeApp } from "firebase/app";
import { getAuth } from "firebase/auth";
import { getFirestore } from "firebase/firestore";
// Public Firebase client identifiers; access is enforced by Auth and Firestore rules.
const config = {
  apiKey: "AIzaSyCseHWf4k-wLb17ccqOygN1VKt9yYbSbw8",
  authDomain: "logit-75c47.firebaseapp.com",
  projectId: "logit-75c47",
  storageBucket: "logit-75c47.firebasestorage.app",
  messagingSenderId: "637637211239",
  appId: "1:637637211239:web:74f67a6fde3448d91a90c2",
};
const app = getApps().length ? getApp() : initializeApp(config);
export const auth = getAuth(app);
export const db = getFirestore(app);
