const admin = require("firebase-admin");
const ExcelJS = require("exceljs");
const os = require("os");
const path = require("path");
const fs = require("fs");
const { google } = require("googleapis");

/* ===============================
   INIT FIREBASE (WAJIB)
   =============================== */
if (!admin.apps.length) {
  admin.initializeApp();
}

/**
 * MAIN EXPORT FUNCTION
 */
async function runExport(type) {
  // TAMBAH: Logging untuk debugging
  console.log(`🚀 Mulai export ${type} pada: ${new Date().toISOString()}`);
  
  try {
    const snapshot = await admin.database().ref("pelanggan").once("value");
    const rawData = snapshot.val() || {};
    
    console.log(`📊 Data ditemukan untuk ${Object.keys(rawData || {}).length} admin`);
    
    // ... [SISIPKAN LOGGING DALAM LOOP] ...
    let pelangganCount = 0;
    Object.entries(rawData).forEach(([adminUid, pelangganList]) => {
      pelangganCount += Object.keys(pelangganList || {}).length;
    });
    console.log(`👥 Total pelanggan: ${pelangganCount}`);

    const workbook = new ExcelJS.Workbook();

    /* ===============================
       SHEET DEFINITIONS (TIDAK DIUBAH)
       =============================== */
    const sheetNasabah = workbook.addWorksheet("DATA_NASABAH");
    sheetNasabah.columns = [
      { header: "pelanggan_id", key: "pelangganId" },
      { header: "nomor_anggota", key: "nomorAnggota" },
      { header: "nama_ktp", key: "namaKtp" },
      { header: "nama_panggilan", key: "namaPanggilan" },
      { header: "nik", key: "nik" },
      { header: "no_hp", key: "noHp" },
      { header: "alamat", key: "alamatRumah" },
      { header: "wilayah", key: "wilayah" },
      { header: "status", key: "status" },
      { header: "status_khusus", key: "statusKhusus" },
      { header: "admin_uid", key: "adminUid" },
      { header: "cabang_id", key: "cabangId" },
      { header: "tanggal_daftar", key: "tanggalDaftar" },
      { header: "tanggal_pelunasan", key: "tanggalPelunasan" }
    ];

    const sheetPinjaman = workbook.addWorksheet("DATA_PINJAMAN");
    sheetPinjaman.columns = [
      { header: "pelanggan_id", key: "pelangganId" },
      { header: "nama_panggilan", key: "namaPanggilan" },
      { header: "pinjaman_ke", key: "pinjamanKe" },
      { header: "besar_pinjaman", key: "besarPinjaman" },
      { header: "pinjaman_disetujui", key: "besarPinjamanDisetujui" },
      { header: "tenor", key: "tenor" },
      { header: "jasa_pinjaman", key: "jasaPinjaman" },
      { header: "admin_fee", key: "adminFee" },
      { header: "total_diterima", key: "totalDiterima" },
      { header: "total_pelunasan", key: "totalPelunasan" },
      { header: "status", key: "status" }
    ];

    const sheetPembayaran = workbook.addWorksheet("PEMBAYARAN");
    sheetPembayaran.columns = [
      { header: "pelanggan_id", key: "pelangganId" },
      { header: "nama_panggilan", key: "namaPanggilan" },
      { header: "tanggal_pembayaran", key: "tanggal" },
      { header: "jumlah", key: "jumlah" },
      { header: "jenis_pembayaran", key: "jenis" },
      { header: "admin_uid", key: "adminUid" },
      { header: "cabang_id", key: "cabangId" }
    ];

    const sheetTunggakan = workbook.addWorksheet("TUNGGAKAN");
    sheetTunggakan.columns = [
      { header: "pelanggan_id", key: "pelangganId" },
      { header: "nama_panggilan", key: "namaPanggilan" },
      { header: "total_pelunasan", key: "totalPelunasan" },
      { header: "total_dibayar", key: "totalDibayar" },
      { header: "sisa_tunggakan", key: "sisaTunggakan" },
      { header: "status", key: "status" }
    ];

    const sheetApproval = workbook.addWorksheet("APPROVAL");
    sheetApproval.columns = [
      { header: "pelanggan_id", key: "pelangganId" },
      { header: "nama_panggilan", key: "namaPanggilan" },
      { header: "approval_pimpinan", key: "approvalPimpinan" },
      { header: "approval_pengawas", key: "approvalPengawas" },
      { header: "tanggal_approval", key: "tanggalApproval" },
      { header: "ditolak_oleh", key: "ditolakOleh" },
      { header: "alasan_penolakan", key: "alasanPenolakan" }
    ];

    const sheetSerahTerima = workbook.addWorksheet("SERAH_TERIMA");
    sheetSerahTerima.columns = [
      { header: "pelanggan_id", key: "pelangganId" },
      { header: "nama_panggilan", key: "namaPanggilan" },
      { header: "status_serah_terima", key: "statusSerahTerima" },
      { header: "tanggal_serah_terima", key: "tanggalSerahTerima" },
      { header: "foto_serah_terima_url", key: "fotoSerahTerimaUrl" }
    ];

    const sheetSummary = workbook.addWorksheet("SUMMARY");
    sheetSummary.columns = [
      { header: "periode", key: "periode" },
      { header: "total_nasabah", key: "totalNasabah" },
      { header: "nasabah_aktif", key: "nasabahAktif" },
      { header: "total_pinjaman", key: "totalPinjaman" },
      { header: "total_pembayaran", key: "totalPembayaran" }
    ];

    /* ===============================
       LOOP DATA (TIDAK DIUBAH) - TAMBAH LOGGING
       =============================== */
    let totalNasabah = 0;
    let nasabahAktif = 0;
    let totalPinjaman = 0;
    let totalPembayaran = 0;

    Object.entries(rawData).forEach(([adminUid, pelangganList]) => {
      Object.entries(pelangganList || {}).forEach(([pelangganId, p]) => {
        totalNasabah++;
        if (p.status === "Aktif") nasabahAktif++;

        sheetNasabah.addRow({
          pelangganId,
          nomorAnggota: p.nomorAnggota || "",
          namaKtp: p.namaKtp || "",
          namaPanggilan: p.namaPanggilan || "",
          nik: p.nik || "",
          noHp: p.noHp || "",
          alamatRumah: p.alamatRumah || "",
          wilayah: p.wilayah || "",
          status: p.status || "",
          statusKhusus: p.statusKhusus || "",
          adminUid,
          cabangId: p.cabangId || "",
          tanggalDaftar: p.tanggalDaftar || "",
          tanggalPelunasan: p.tanggalPelunasan || ""
        });

        sheetPinjaman.addRow({
          pelangganId,
          namaPanggilan: p.namaPanggilan,
          pinjamanKe: p.pinjamanKe,
          besarPinjaman: p.besarPinjaman,
          besarPinjamanDisetujui: p.besarPinjamanDisetujui,
          tenor: p.tenor,
          jasaPinjaman: p.jasaPinjaman,
          adminFee: p.admin,
          totalDiterima: p.totalDiterima,
          totalPelunasan: p.totalPelunasan,
          status: p.status
        });

        let totalDibayar = 0;
        (p.pembayaranList || []).forEach(pay => {
          totalDibayar += pay.jumlah || 0;
          totalPembayaran += pay.jumlah || 0;

          sheetPembayaran.addRow({
            pelangganId,
            namaPanggilan: p.namaPanggilan,
            tanggal: pay.tanggal,
            jumlah: pay.jumlah,
            jenis: pay.subPembayaran ? "SUB" : "UTAMA",
            adminUid,
            cabangId: p.cabangId || ""
          });
        });

        totalPinjaman += p.totalPelunasan || 0;

        sheetTunggakan.addRow({
          pelangganId,
          namaPanggilan: p.namaPanggilan,
          totalPelunasan: p.totalPelunasan || 0,
          totalDibayar,
          sisaTunggakan: (p.totalPelunasan || 0) - totalDibayar,
          status: p.status
        });

        sheetApproval.addRow({
          pelangganId,
          namaPanggilan: p.namaPanggilan,
          approvalPimpinan: p.approvalPimpinan || "",
          approvalPengawas: p.approvalPengawas || "",
          tanggalApproval: p.tanggalApproval || "",
          ditolakOleh: p.ditolakOleh || "",
          alasanPenolakan: p.alasanPenolakan || ""
        });

        sheetSerahTerima.addRow({
          pelangganId,
          namaPanggilan: p.namaPanggilan,
          statusSerahTerima: p.statusSerahTerima || "",
          tanggalSerahTerima: p.tanggalSerahTerima || "",
          fotoSerahTerimaUrl: p.fotoSerahTerimaUrl || ""
        });
      });
    });

    console.log(`✅ Data diproses: ${totalNasabah} nasabah, ${nasabahAktif} aktif`);

    sheetSummary.addRow({
      periode: type,
      totalNasabah,
      nasabahAktif,
      totalPinjaman,
      totalPembayaran
    });

    /* ===============================
       WRITE FILE - TAMBAH ERROR HANDLING
       =============================== */
    const fileName = `EXPORT_${type}_${new Date().toISOString().slice(0, 10)}.xlsx`;
    const filePath = path.join(os.tmpdir(), fileName);

    console.log(`💾 Menulis file ke: ${filePath}`);
    await workbook.xlsx.writeFile(filePath);

    // VERIFIKASI FILE DIBUAT
    if (!fs.existsSync(filePath)) {
      throw new Error(`❌ File gagal dibuat di ${filePath}`);
    }
    
    const fileStats = fs.statSync(filePath);
    console.log(`📁 File berhasil dibuat: ${fileName} (${fileStats.size} bytes)`);

    // UPLOAD KE GOOGLE DRIVE
    console.log(`☁️  Mengupload ke Google Drive...`);
    await uploadToGoogleDrive(filePath, fileName);
    console.log(`✅ Upload selesai!`);

    // CLEANUP
    fs.unlinkSync(filePath);
    console.log(`🧹 File sementara dihapus`);

    return { success: true, fileName, totalNasabah, totalPinjaman, totalPembayaran };
    
  } catch (error) {
    // TAMBAH: Error handling untuk debugging
    console.error(`❌ ERROR dalam runExport:`, error);
    throw error; // Re-throw agar Firebase Functions tahu ada error
  }
}

/* ===============================
   EXPORT UNTUK SCHEDULER - TAMBAH ASYNC/AWAIT
   =============================== */
module.exports = {
  exportHarian: async () => {
    console.log("⏰ Trigger export harian dijalankan");
    try {
      const result = await runExport("HARIAN");
      console.log("🎉 Export harian berhasil:", result);
      return result;
    } catch (error) {
      console.error("🔥 Export harian GAGAL:", error);
      throw error; // Penting: throw error agar Cloud Functions mencatat failure
    }
  }
};

/* ===============================
   GOOGLE DRIVE UPLOAD - TAMBAH ERROR HANDLING
   =============================== */
async function uploadToGoogleDrive(localFilePath, fileName) {
  try {
    console.log(`🔐 Autentikasi Google Drive via OAuth2 (Production)...`);
    
    // TEMPEL HASIL SALINAN BARU DI SINI
    const CLIENT_ID = "711615046999-i5ln3opbldlsla5d6qcndsin3t18bo17.apps.googleusercontent.com";
    const CLIENT_SECRET = "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"; 
    const REFRESH_TOKEN = "1//043hEpUn9GaSyCgYIARAAGAQSNwF-L9IrU1BHUAginTGhTn74Ow__cEQhxMCZ7X120uFizRUmDXpL1lHBnrNkjpCXWq3scWJKm-4";

    const oauth2Client = new google.auth.OAuth2(
      CLIENT_ID,
      CLIENT_SECRET,
      "https://developers.google.com/oauthplayground"
    );

    oauth2Client.setCredentials({ refresh_token: REFRESH_TOKEN });

    const drive = google.drive({ version: "v3", auth: oauth2Client });
    const folderId = "1SXY3AW45Va5MfUVb1_OKaVrslnyTkVhh"; 

    const response = await drive.files.create({
      requestBody: {
        name: fileName,
        parents: [folderId]
      },
      media: {
        mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        body: fs.createReadStream(localFilePath)
      },
      fields: 'id, name, webViewLink'
    });

    console.log(`✅ BERHASIL! File terupload: ${response.data.name}`);
    return response.data;
    
  } catch (error) {
    console.error(`❌ ERROR Drive: ${error.message}`);
    throw new Error(`Gagal upload: ${error.message}`);
  }
}