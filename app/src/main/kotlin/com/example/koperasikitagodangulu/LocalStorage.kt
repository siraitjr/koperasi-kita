package com.example.koperasikitagodangulu

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.util.Log

// Buat extension DataStore
val Context.dataStore by preferencesDataStore(name = "kredit_kita_prefs")

object LocalStorage {
    private val KEY_PELANGGAN = stringPreferencesKey("data_pelanggan")
    private val KEY_DARK_MODE = booleanPreferencesKey("dark_mode")
    private val gson = Gson()

    // Simpan data ke DataStore
    suspend fun simpanDataPelanggan(context: Context, daftar: List<Pelanggan>, adminUid: String) {
        withContext(Dispatchers.IO) {
            try {
                val filename = "pelanggan_${adminUid}.json"
                val file = File(context.filesDir, filename)
                val json = gson.toJson(daftar)
                file.writeText(json)
            } catch (e: Exception) {
                // jangan crash app karena local storage gagal; log saja
                e.printStackTrace()
            }
        }
    }

    // Ambil data dari DataStore
    suspend fun ambilDataPelanggan(context: Context, adminUid: String): List<Pelanggan> {
        return withContext(Dispatchers.IO) {
            try {
                val filename = "pelanggan_${adminUid}.json"
                val file = File(context.filesDir, filename)
                if (!file.exists()) return@withContext emptyList<Pelanggan>()
                val json = file.readText()
                val type = object : TypeToken<List<Pelanggan>>() {}.type
                val list: List<Pelanggan> = gson.fromJson(json, type) ?: emptyList()
                list
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    suspend fun simpanTema(context: Context, isDark: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DARK_MODE] = isDark
        }
    }

    suspend fun ambilTema(context: Context): Boolean {
        return context.dataStore.data
            .map { it[KEY_DARK_MODE] ?: false }
            .first()
    }

    suspend fun hapusDataPelanggan(context: Context, adminUid: String) {
        withContext(Dispatchers.IO) {
            try {
                val filename = "pelanggan_${adminUid}.json"
                val file = File(context.filesDir, filename)
                if (file.exists()) {
                    file.delete()
                    Log.d("LocalStorage", "✅ File $filename berhasil dihapus")
                }
                // ✅ TAMBAH: Unit sebagai return value eksplisit
                Unit
            } catch (e: Exception) {
                Log.e("LocalStorage", "❌ Gagal hapus data pelanggan: ${e.message}")
                // ✅ TAMBAH: Unit di catch block juga
                Unit
            }
        }
    }
}