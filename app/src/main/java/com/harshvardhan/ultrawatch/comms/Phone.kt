package com.harshvardhan.ultrawatch.comms

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.harshvardhan.ultrawatch.core.Contact

/** Contacts, calling and SMS. On Bluetooth-only watches calls route through the paired phone. */
object Phone {
    private fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun hasTelephony(ctx: Context) = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    fun contacts(ctx: Context): List<Contact> {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = LinkedHashMap<String, Contact>()
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.STARRED,
        )
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                if (out.containsKey(id)) continue
                out[id] = Contact(id, c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3) == 1)
            }
        }
        return out.values.toList()
    }

    /** Places the call directly when permitted, otherwise opens the dialer with the number filled in. */
    fun call(ctx: Context, number: String) {
        val clean = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        val action = if (has(ctx, Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        ctx.startActivity(Intent(action, Uri.parse("tel:" + Uri.encode(clean))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Sends an SMS from an LTE watch. Returns false when the watch can't send SMS itself. */
    fun sendSms(ctx: Context, number: String, text: String): Boolean {
        if (!hasTelephony(ctx) || !has(ctx, Manifest.permission.SEND_SMS)) return false
        return try {
            val sms = ctx.getSystemService(SmsManager::class.java)
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
            true
        } catch (_: Exception) { false }
    }

    /** Fallback: hand the text to the watch's messaging app (works through the phone on Bluetooth watches). */
    fun composeSms(ctx: Context, number: String, text: String) {
        ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
            .putExtra("sms_body", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
