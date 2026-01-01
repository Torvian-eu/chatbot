package eu.torvian.chatbot.app.utils.platform

/**
 * WasmJS implementation of file picker.
 *
 * Note: File picker is not supported in the WASM/browser environment.
 * This stub implementation always returns an error.
 */
actual suspend fun selectFiles(
    includeContent: Boolean,
    maxContentSize: Long,
    initialDirectory: String?
): FilePickerResult {
    return FilePickerResult.Error("File picker is not supported in the browser environment")
}

/**
 * WasmJS implementation of re-reading file content.
 *
 * Note: File re-reading is not supported in the WASM/browser environment.
 * This stub implementation always returns an error.
 */
actual suspend fun reReadFileContent(
    absolutePath: String,
    maxContentSize: Long
): ReReadFileResult {
    return ReReadFileResult.Error("File re-reading is not supported in the browser environment")
}
