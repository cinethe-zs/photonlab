package com.photonlab.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.photonlab.data.lut.LutParser
import com.photonlab.domain.model.LutFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LutRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Load and parse a LUT from a content URI (picked by the user). */
    suspend fun loadLut(uri: Uri): Result<LutFile> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = resolveDisplayName(uri)
            LutParser.parse(context, uri, displayName)
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx != -1 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        // Fallback: extract filename from URI path
        return uri.lastPathSegment ?: "lut.cube"
    }
}
