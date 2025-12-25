package eu.torvian.chatbot.app.service.clipboard

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Desktop implementation of [ClipboardService] using Java AWT.
 *
 * Uses [java.awt.Toolkit] to access the system clipboard and
 * [StringSelection] to copy text content.
 */
class ClipboardServiceDesktop : ClipboardService {

    override suspend fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val selection = StringSelection(text)
        clipboard.setContents(selection, selection)
    }
}

