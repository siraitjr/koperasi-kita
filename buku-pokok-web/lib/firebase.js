// lib/firebase.js
// =========================================================================
// Firebase Client SDK Configuration
// =========================================================================
// PENTING: Ganti dengan konfigurasi Firebase project kamu
// Ambil dari Firebase Console > Project Settings > General > Your apps
// =========================================================================

import { initializeApp, getApps } from 'firebase/app';
import { getAuth } from 'firebase/auth';
import { getStorage } from 'firebase/storage';
import { getDatabase } from 'firebase/database';

const firebaseConfig = {
  apiKey: "AIzaSyDPVVlPksKLL9PIfcxx8tlGViftddwVrCE",
  authDomain: "koperasikitagodangulu.firebaseapp.com",
  databaseURL: "https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app",
  projectId: "koperasikitagodangulu",
  storageBucket: "koperasikitagodangulu.firebasestorage.app",
  messagingSenderId: "711615046999",
  appId: "1:711615046999:web:6461b08dbbdfa5f4304a12"
};

// Initialize Firebase (prevent re-initialization on hot reload)
const app = getApps().length === 0 ? initializeApp(firebaseConfig) : getApps()[0];
const auth = getAuth(app);
const storage = getStorage(app);
const database = getDatabase(app);

export { app, auth, storage, database };
