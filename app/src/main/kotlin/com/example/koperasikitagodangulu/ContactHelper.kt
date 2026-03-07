package com.example.koperasikitagodangulu

import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.LocalContext
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.Manifest
import android.util.Log
import android.net.Uri

object ContactHelper {
    @Composable
    fun getContactLauncher(phoneNumberState: MutableState<String>): Pair<() -> Unit, () -> Unit> {
        val context = LocalContext.current
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickContact()
        ) { uri ->
            uri?.let {
                try {
                    getPhoneNumberFromContact(context.contentResolver, uri)?.let { number ->
                        Log.d("ContactHelper", "Updating state with number: $number")
                        phoneNumberState.value = number
                    }
                } catch (e: Exception) {
                    Log.e("ContactHelper", "Error processing contact", e)
                }
            }
        }

        val requestPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) launcher.launch(null)
        }

        return Pair(
            first = {
                if (hasContactsPermission(context)) {
                    launcher.launch(null)
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                }
            },
            second = { launcher.launch(null) }
        )
    }

    private fun hasContactsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getPhoneNumberFromContact(
        contentResolver: ContentResolver,
        contactUri: Uri
    ): String? {
        Log.d("ContactHelper", "Contact URI: $contactUri")
        // Pertama, dapatkan contact ID dari URI yang dipilih
        val contactId = getContactId(contentResolver, contactUri) ?: run {
            Log.d("ContactHelper", "Failed to get contact ID")
            return null
        }

        // Kemudian query nomor telepon menggunakan contact ID
        val phoneUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
        val selectionArgs = arrayOf(contactId)

        Log.d("ContactHelper", "Querying phone numbers for contact ID: $contactId")

        return contentResolver.query(
            phoneUri,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val number = cursor.getString(0)
                    ?.replace("[^0-9]".toRegex(), "")
                    ?.takeIf { it.isNotEmpty() }
                Log.d("ContactHelper", "Extracted number: ${number ?: "NULL"}")
                number
            } else {
                Log.d("ContactHelper", "No phone number found for this contact")
                null
            }
        }
    }

    private fun getContactId(contentResolver: ContentResolver, contactUri: Uri): String? {
        return contentResolver.query(
            contactUri,
            arrayOf(ContactsContract.Contacts._ID),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)
            } else {
                null
            }
        }
    }
}