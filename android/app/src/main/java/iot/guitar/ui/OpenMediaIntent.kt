package iot.guitar.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Hands a MediaStore video/audio item to the user's player of choice (chooser, read grant). */
fun Context.openMedia(uri: Uri, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(intent, null))
}
