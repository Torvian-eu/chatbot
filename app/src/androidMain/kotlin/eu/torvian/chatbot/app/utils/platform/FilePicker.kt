package eu.torvian.chatbot.app.utils.platform

/**
 * Android implementation of file picker.
 *
 * Note: Full implementation would use Activity Results API for file picking.
 * This stub implementation returns an error.
 *
 * TODO: Implement using registerForActivityResult with GetMultipleContents contract
 */
actual suspend fun selectFiles(
    includeContent: Boolean,
    maxContentSize: Long,
    initialDirectory: String?
): FilePickerResult {
    return FilePickerResult.Error("File picker requires Activity context on Android. Use AndroidFilePicker instead.")
}

/**
 * Android implementation of re-reading file content.
 *
 * Note: File re-reading requires proper Android file access implementation.
 * This stub implementation returns an error.
 *
 * TODO: Implement using ContentResolver and proper file access
 */
actual suspend fun reReadFileContent(
    absolutePath: String,
    maxContentSize: Long
): ReReadFileResult {
    return ReReadFileResult.Error("File re-reading requires Activity context on Android.")
}
