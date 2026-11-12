package io.github.nath_goa.usugami.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import io.github.nath_goa.usugami.data.PdfFile

/**
 * The system "Save as" screen for a new document named as the file to save, of its type: a PDF, an
 * image or a text file. What `ActivityResultContracts.CreateDocument` builds, with the type taken
 * from the file rather than fixed.
 */
internal class CreateFileDocument : ActivityResultContract<PdfFile, Uri?>() {
    override fun createIntent(context: Context, input: PdfFile): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).setType(input.type.mime).putExtra(Intent.EXTRA_TITLE, input.name)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent.takeIf { resultCode == Activity.RESULT_OK }?.data
}
