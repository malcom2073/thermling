package com.thermling.platform

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Storage Access Framework dialogs for Android. The activity result contracts
 * must be registered before the activity is started, so this class is created
 * from [ComponentActivity.onCreate]. The suspend functions bridge the
 * callback-based contracts to the coroutine-based [Platform] interface.
 */
class AndroidFileDialogs(private val activity: ComponentActivity) {
    private var pendingImage: CancellableContinuation<Uri?>? = null
    private var pendingOpen: CancellableContinuation<Uri?>? = null
    private var pendingCreate: CancellableContinuation<Uri?>? = null

    private val imageLauncher = activity.registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        pendingImage?.let { resume(it, uri) }
        pendingImage = null
    }

    private val openLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) persistRead(uri)
        pendingOpen?.let { resume(it, uri) }
        pendingOpen = null
    }

    private val createLauncher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) persistReadWrite(uri)
        pendingCreate?.let { resume(it, uri) }
        pendingCreate = null
    }

    suspend fun pickImage(): Uri? = await(
        store = { pendingImage = it },
        clear = { pendingImage = null },
        launch = { imageLauncher.launch("image/*") },
    )

    suspend fun openDocument(): Uri? = await(
        store = { pendingOpen = it },
        clear = { pendingOpen = null },
        launch = { openLauncher.launch(arrayOf("*/*")) },
    )

    suspend fun createDocument(suggestedName: String): Uri? = await(
        store = { pendingCreate = it },
        clear = { pendingCreate = null },
        launch = { createLauncher.launch(suggestedName) },
    )

    private suspend fun await(
        store: (CancellableContinuation<Uri?>) -> Unit,
        clear: () -> Unit,
        launch: () -> Unit,
    ): Uri? = suspendCancellableCoroutine { continuation ->
        store(continuation)
        continuation.invokeOnCancellation { clear() }
        try {
            launch()
        } catch (error: Exception) {
            clear()
            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
        }
    }

    private fun resume(continuation: CancellableContinuation<Uri?>, uri: Uri?) {
        if (continuation.isActive) continuation.resume(uri)
    }

    private fun persistRead(uri: Uri) {
        runCatching {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun persistReadWrite(uri: Uri) {
        runCatching {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }
}
