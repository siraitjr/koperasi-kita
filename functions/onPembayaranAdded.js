// functions/onPembayaranAdded.js
// =========================================================================
// CLOUD FUNCTION: ON PEMBAYARAN ADDED (100% OTOMATIS) - v3
// =========================================================================
//
// PERUBAHAN v3:
// âœ… Menyimpan ke node pembayaran_harian untuk akses cepat Pimpinan
// âœ… Menyimpan ke node event_harian/nasabah_baru saat pencairan
// âœ… Menyimpan ke node event_harian/nasabah_lunas saat lunas
// âœ… Update pelanggan_bermasalah saat nasabah lunas (hapus dari list)
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');

const db = admin.database();

const { processPembayaranBaru, getTodayIndonesia, incrementAdminSummary, incrementCabangSummary, incrementGlobalSummary } = require('./summaryHelpers');

// ✅ JURNAL TRANSAKSI - Pencatatan permanen untuk pembukuan profesional
const jurnal = require('./jurnalTransaksi');

// =========================================================================
// HELPER: Simpan ke node pembayaran_harian
// =========================================================================
async function saveToPembayaranHarian(adminUid, pelangganId, pembayaran, jenis) {
    try {
        const today = getTodayIndonesia();
        
        // Dapatkan data admin dan cabang
        const adminSnap = await db.ref(`metadata/admins/${adminUid}`).once('value');
        const adminData = adminSnap.val();
        
        if (!adminData || !adminData.cabang) {
            console.log('âš ï¸ Admin atau cabang tidak ditemukan');
            return;
        }
        
        const cabangId = adminData.cabang;
        
        // Dapatkan nama pelanggan
        const pelangganSnap = await db.ref(`pelanggan/${adminUid}/${pelangganId}`).once('value');
        const pelangganData = pelangganSnap.val();
        
        const pembayaranData = {
            pelangganId: pelangganId,
            namaPanggilan: pelangganData?.namaPanggilan || 'Unknown',
            namaKtp: pelangganData?.namaKtp || '',
            adminUid: adminUid,
            // âœ… PERBAIKAN: Prioritaskan email agar Pimpinan tahu siapa adminnya
            adminName: adminData.name || adminData.email || '',
            adminEmail: adminData.email || '',
            jumlah: pembayaran.jumlah || 0,
            jenis: jenis, // "cicilan", "tambah_bayar", atau "pencairan"
            tanggal: pembayaran.tanggal || today,
            timestamp: admin.database.ServerValue.TIMESTAMP
        };
        
        // Simpan ke pembayaran_harian/{cabangId}/{tanggal}/{autoId}
        await db.ref(`pembayaran_harian/${cabangId}/${today}`).push(pembayaranData);
        
        console.log(`âœ… Saved to pembayaran_harian: ${cabangId}/${today} - ${pelangganData?.namaPanggilan} - Rp ${pembayaran.jumlah}`);
        
    } catch (error) {
        console.error('âŒ Error saving to pembayaran_harian:', error.message);
    }
}

// =========================================================================
// âœ… HELPER: Simpan ke event_harian/nasabah_baru
// =========================================================================
async function saveToNasabahBaru(adminUid, pelangganId, pelanggan) {
    try {
        const today = getTodayIndonesia();
        
        const adminSnap = await db.ref(`metadata/admins/${adminUid}`).once('value');
        const adminData = adminSnap.val();
        
        if (!adminData || !adminData.cabang) return;
        
        const cabangId = adminData.cabang;
        
        const nasabahBaruData = {
            namaPanggilan: pelanggan.namaPanggilan || '',
            namaKtp: pelanggan.namaKtp || '',
            adminUid: adminUid,
            // âœ… PERBAIKAN: Prioritaskan email
            adminName: adminData.name || adminData.email || '',
            besarPinjaman: pelanggan.besarPinjaman || 0,
            totalDiterima: pelanggan.totalDiterima || 0,
            wilayah: pelanggan.wilayah || '',
            tanggalDaftar: today,
            timestamp: admin.database.ServerValue.TIMESTAMP
        };
        
        await db.ref(`event_harian/${cabangId}/${today}/nasabah_baru/${pelangganId}`).set(nasabahBaruData);
        
        console.log(`âœ… Saved to event_harian/nasabah_baru: ${pelanggan.namaPanggilan}`);
        
    } catch (error) {
        console.error('âŒ Error saving to nasabah_baru:', error.message);
    }
}

// =========================================================================
// âœ… HELPER: Simpan ke event_harian/nasabah_lunas
// =========================================================================
async function saveToNasabahLunas(adminUid, pelangganId, pelanggan) {
    try {
        const today = getTodayIndonesia();
        
        const adminSnap = await db.ref(`metadata/admins/${adminUid}`).once('value');
        const adminData = adminSnap.val();
        
        if (!adminData || !adminData.cabang) return;
        
        const cabangId = adminData.cabang;
        
        // Hitung total dibayar — ✅ FIX: Exclude entry 'Bunga...'
        let totalDibayar = 0;
        if (pelanggan.pembayaranList) {
            const pembayaranList = Array.isArray(pelanggan.pembayaranList)
                ? pelanggan.pembayaranList
                : Object.values(pelanggan.pembayaranList || {});
            pembayaranList.forEach(p => {
                if (p && p.tanggal && p.tanggal.startsWith('Bunga')) return;
                totalDibayar += p.jumlah || 0;
                if (p.subPembayaran) {
                    const subList = Array.isArray(p.subPembayaran)
                        ? p.subPembayaran
                        : Object.values(p.subPembayaran || {});
                    subList.forEach(sub => totalDibayar += sub.jumlah || 0);
                }
            });
        }
        
        const nasabahLunasData = {
            namaPanggilan: pelanggan.namaPanggilan || '',
            namaKtp: pelanggan.namaKtp || '',
            adminUid: adminUid,
            // âœ… PERBAIKAN: Prioritaskan email
            adminName: adminData.name || adminData.email || '',
            totalPinjaman: pelanggan.totalPelunasan || 0,
            totalDibayar: totalDibayar,
            wilayah: pelanggan.wilayah || '',
            tanggalLunas: today,
            timestamp: admin.database.ServerValue.TIMESTAMP
        };
        
        await db.ref(`event_harian/${cabangId}/${today}/nasabah_lunas/${pelangganId}`).set(nasabahLunasData);
        
        // Hapus dari pelanggan_bermasalah jika ada
        await db.ref(`pelanggan_bermasalah/${cabangId}/${pelangganId}`).remove();
        
        console.log(`âœ… Saved to event_harian/nasabah_lunas: ${pelanggan.namaPanggilan}`);
        
    } catch (error) {
        console.error('âŒ Error saving to nasabah_lunas:', error.message);
    }
}

// =========================================================================
// âœ… HELPER: Cek apakah nasabah sudah lunas
// =========================================================================
function isNasabahLunas(pelanggan) {
    const totalPelunasan = pelanggan.totalPelunasan || 0;
    if (totalPelunasan <= 0) return false;

    let totalDibayar = 0;
    if (pelanggan.pembayaranList) {
        const pembayaranList = Array.isArray(pelanggan.pembayaranList)
            ? pelanggan.pembayaranList
            : Object.values(pelanggan.pembayaranList || {});
        pembayaranList.forEach(p => {
            // ✅ FIX: Exclude entry 'Bunga...' — konsisten dengan calculateTotalDibayar di summaryHelpers & bukuPokokApi
            if (p && p.tanggal && p.tanggal.startsWith('Bunga')) return;
            totalDibayar += p.jumlah || 0;
            if (p.subPembayaran) {
                const subList = Array.isArray(p.subPembayaran)
                    ? p.subPembayaran
                    : Object.values(p.subPembayaran || {});
                subList.forEach(sub => totalDibayar += sub.jumlah || 0);
            }
        });
    }

    return totalDibayar >= totalPelunasan;
}

// =========================================================================
// TRIGGER: Pembayaran Cicilan Baru
// =========================================================================
exports.onPembayaranAdded = functions.database
    .ref('/pelanggan/{adminUid}/{pelangganId}/pembayaranList/{pembayaranIndex}')
    .onCreate(async (snapshot, context) => {
        const { adminUid, pelangganId } = context.params;
        const pembayaran = snapshot.val();
        
        console.log(`ðŸ’° Payment: Rp ${pembayaran.jumlah} for ${pelangganId}`);
        
        try {
            // 1. Update summary (existing logic)
            await processPembayaranBaru(adminUid, pelangganId, pembayaran);
            
            // 2. Simpan ke pembayaran_harian
            await saveToPembayaranHarian(adminUid, pelangganId, pembayaran, 'cicilan');
            
            // 3. âœ… Cek apakah nasabah lunas setelah pembayaran ini
            const pelangganSnap = await db.ref(`pelanggan/${adminUid}/${pelangganId}`).once('value');
            const pelanggan = pelangganSnap.val();
            
            if (pelanggan && isNasabahLunas(pelanggan)) {
                // FIX: Skip jika ini pembayaran sisa utang lama dari top-up/lanjut pinjaman
                // Karena saat top-up, totalPelunasan baru mungkin belum ter-write
                const pinjamanKe = pelanggan.pinjamanKe || 1;
                const sisaUtangLama = pelanggan.sisaUtangLamaSebelumTopUp || 0;
                const isTopUpPayment = pinjamanKe > 1 && sisaUtangLama > 0 && pembayaran.jumlah === sisaUtangLama;

                if (isTopUpPayment) {
                    console.log('Skip lunas check - pembayaran sisa utang lama top-up Rp ' + sisaUtangLama);
                } else {
                    await saveToNasabahLunas(adminUid, pelangganId, pelanggan);
                }
            }

            // ✅ JURNAL: Catat pembayaran cicilan ke jurnal permanen
            // (di luar if-lunas agar SEMUA pembayaran tercatat, bukan hanya yang lunas)
            if (pelanggan) {
                const adminSnap2 = await db.ref(`metadata/admins/${adminUid}`).once('value');
                const adminData2 = adminSnap2.val();
                if (adminData2) {
                    await jurnal.catatPembayaranCicilan(adminUid, pelangganId, pembayaran, pelanggan, adminData2);

                    // Jika lunas, catat juga marker lunas
                    if (isNasabahLunas(pelanggan)) {
                        const pinjamanKe = pelanggan.pinjamanKe || 1;
                        const sisaUtangLama = pelanggan.sisaUtangLamaSebelumTopUp || 0;
                        const isTopUpPayment = pinjamanKe > 1 && sisaUtangLama > 0 && pembayaran.jumlah === sisaUtangLama;
                        if (!isTopUpPayment) {
                            await jurnal.catatLunas(adminUid, pelangganId, pelanggan, adminData2);
                        }
                    }
                }
            }

            return null;
        } catch (error) {
            console.error(`âŒ Error: ${error.message}`);
            return null;
        }
    });

// =========================================================================
// TRIGGER: Sub-Pembayaran (Tambah Bayar)
// =========================================================================
exports.onSubPembayaranAdded = functions.database
    .ref('/pelanggan/{adminUid}/{pelangganId}/pembayaranList/{pembayaranIndex}/subPembayaran/{subIndex}')
    .onCreate(async (snapshot, context) => {
        const { adminUid, pelangganId } = context.params;
        const subPembayaran = snapshot.val();
        
        console.log(`ðŸ’° Sub-payment: Rp ${subPembayaran.jumlah} for ${pelangganId}`);
        
        try {
            // 1. Update summary (existing logic)
            await processPembayaranBaru(adminUid, pelangganId, subPembayaran);
            
            // 2. Simpan ke pembayaran_harian
            await saveToPembayaranHarian(adminUid, pelangganId, subPembayaran, 'tambah_bayar');
            
            // 3. âœ… Cek apakah nasabah lunas setelah pembayaran ini
            const pelangganSnap = await db.ref(`pelanggan/${adminUid}/${pelangganId}`).once('value');
            const pelanggan = pelangganSnap.val();

            if (pelanggan && isNasabahLunas(pelanggan)) {
                // FIX: Skip jika ini terkait top-up/lanjut pinjaman (konsisten dengan onPembayaranAdded)
                const pinjamanKe = pelanggan.pinjamanKe || 1;
                const sisaUtangLama = pelanggan.sisaUtangLamaSebelumTopUp || 0;
                const isTopUpRelated = pinjamanKe > 1 && sisaUtangLama > 0 && subPembayaran.jumlah === sisaUtangLama;

                if (!isTopUpRelated) {
                    await saveToNasabahLunas(adminUid, pelangganId, pelanggan);
                }
            }

            // ✅ JURNAL: Catat tambah bayar ke jurnal permanen
            if (pelanggan) {
                const adminSnapJ = await db.ref(`metadata/admins/${adminUid}`).once('value');
                const adminDataJ = adminSnapJ.val();
                if (adminDataJ) {
                    await jurnal.catatTambahBayar(adminUid, pelangganId, subPembayaran, pelanggan, adminDataJ);

                    if (isNasabahLunas(pelanggan)) {
                        const pinjamanKe = pelanggan.pinjamanKe || 1;
                        const sisaUtangLama = pelanggan.sisaUtangLamaSebelumTopUp || 0;
                        const isTopUpRelated = pinjamanKe > 1 && sisaUtangLama > 0 && subPembayaran.jumlah === sisaUtangLama;
                        if (!isTopUpRelated) {
                            await jurnal.catatLunas(adminUid, pelangganId, pelanggan, adminDataJ);
                        }
                    }
                }
            }

            return null;
        } catch (error) {
            console.error(`âŒ Error: ${error.message}`);
            return null;
        }
    });

// =========================================================================
// âœ… TRIGGER untuk Pencairan Pinjaman Baru (Status berubah ke Aktif)
// =========================================================================
exports.onPelangganApproved = functions.database
    .ref('/pelanggan/{adminUid}/{pelangganId}/status')
    .onUpdate(async (change, context) => {
        const { adminUid, pelangganId } = context.params;
        const beforeStatus = change.before.val();
        const afterStatus = change.after.val();
        
        // Hanya proses jika status berubah ke "Aktif"
        if (afterStatus?.toLowerCase() !== 'aktif') return null;
        if (beforeStatus?.toLowerCase() === 'aktif') return null;
        
        console.log(`ðŸŽ‰ Pelanggan approved: ${pelangganId}`);
        
        try {
            const today = getTodayIndonesia();
            
            // Dapatkan data pelanggan
            const pelangganSnap = await db.ref(`pelanggan/${adminUid}/${pelangganId}`).once('value');
            const pelanggan = pelangganSnap.val();
            
            if (!pelanggan) return null;
            
            // Hitung jumlah yang dicairkan
            let jumlahDicairkan = 0;
            if (pelanggan.totalDiterima > 0) {
                jumlahDicairkan = pelanggan.totalDiterima;
            } else if (pelanggan.admin > 0 && pelanggan.simpanan > 0) {
                jumlahDicairkan = pelanggan.besarPinjaman - pelanggan.admin - pelanggan.simpanan;
            } else {
                const adminFee = Math.round(pelanggan.besarPinjaman * 0.05);
                const simpanan = Math.round(pelanggan.besarPinjaman * 0.05);
                jumlahDicairkan = pelanggan.besarPinjaman - adminFee - simpanan;
            }
            
            // 1. Simpan ke pembayaran_harian sebagai pencairan
            const pencairanData = {
                jumlah: jumlahDicairkan,
                tanggal: today
            };
            await saveToPembayaranHarian(adminUid, pelangganId, pencairanData, 'pencairan');
            
            // ✅ PERBAIKAN: Sisa utang pinjaman lama TIDAK boleh masuk ke pembayaranList pinjaman baru
            // karena akan dianggap sebagai cicilan pinjaman baru dan mengurangi sisa utang.
            // Sisa utang hanya dicatat di:
            // 1. jurnal_transaksi (catatPelunasanSisaUtang) — untuk pembukuan permanen
            // 2. pembayaran_harian — untuk laporan harian
            // 3. sisaUtangLamaSebelumTopUp field — untuk referensi
            const sisaUtangLama = pelanggan.sisaUtangLamaSebelumTopUp || 0;
            const pinjamanKe = pelanggan.pinjamanKe || 1;
            if (sisaUtangLama > 0 && pinjamanKe > 1) {
                // Catat ke pembayaran_harian sebagai pelunasan sisa utang (bukan cicilan)
                const pelunasanEntry = {
                    jumlah: sisaUtangLama,
                    tanggal: today,
                    subPembayaran: []
                };
                await saveToPembayaranHarian(adminUid, pelangganId, pelunasanEntry, 'pelunasan_sisa_utang');
                console.log(`✅ Sisa utang Rp ${sisaUtangLama} dicatat ke pembayaran_harian (TIDAK ke pembayaranList)`);
            }
            
            // 2. ✅ Simpan ke event_harian/nasabah_baru
            await saveToNasabahBaru(adminUid, pelangganId, pelanggan);

            // 3. ✅ JURNAL: Catat pencairan pinjaman ke jurnal permanen
            const adminSnapJ = await db.ref(`metadata/admins/${adminUid}`).once('value');
            const adminDataJ = adminSnapJ.val();
            if (adminDataJ) {
                await jurnal.catatPencairanPinjaman(adminUid, pelangganId, pelanggan, adminDataJ, jumlahDicairkan);

                // Jika lanjut pinjaman, catat juga pelunasan sisa utang lama
                if (sisaUtangLama > 0 && pinjamanKe > 1) {
                    await jurnal.catatPelunasanSisaUtang(adminUid, pelangganId, pelanggan, adminDataJ, sisaUtangLama);

                    // ✅ Update summary: pelunasan sisa utang dihitung sebagai pembayaranHariIni
                    const pelunasanDelta = { pembayaranHariIniChange: sisaUtangLama };
                    const cabangIdJ = adminDataJ.cabang;
                    await Promise.all([
                        incrementAdminSummary(adminUid, pelunasanDelta),
                        cabangIdJ ? incrementCabangSummary(cabangIdJ, pelunasanDelta) : Promise.resolve(),
                        incrementGlobalSummary(pelunasanDelta)
                    ]);
                    console.log(`✅ Summary pembayaranHariIni +Rp ${sisaUtangLama} (pelunasan sisa utang)`);
                }
            }

            console.log(`✅ Pencairan recorded: Rp ${jumlahDicairkan}`);

            return null;
        } catch (error) {
            console.error(`âŒ Error: ${error.message}`);
            return null;
        }
    });