package eu.torvian.chatbot.app.compose.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.service.security.CertificateDetails

/**
 * A dialog that informs the user about an untrusted or changed server certificate and
 * asks whether to trust it.
 *
 * The dialog adapts its title and messaging depending on whether an `oldFingerprint`
 * is present (i.e. whether the certificate changed since the last known fingerprint).
 *
 * @param details The certificate details to present to the user.
 * @param onAccept Callback invoked when the user chooses to trust the certificate.
 * @param onReject Callback invoked when the user rejects/trashes the certificate.
 */
@Composable
fun CertificateWarningDialog(
    details: CertificateDetails,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = {
            Text(
                if (details.oldFingerprint == null) "Untrusted Certificate" else "Certificate Has Changed"
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (details.oldFingerprint == null) {
                        "The server presented a certificate that is not trusted by your device. This could be because it's a self-signed certificate for a local server."
                    } else {
                        "The server's certificate has changed since your last connection. This could be a security risk (man-in-the-middle attack) or the server administrator may have updated the certificate."
                    }
                )
                Text("Do you want to trust this new certificate?", fontWeight = FontWeight.Bold)

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text("New Certificate Details:", style = MaterialTheme.typography.titleSmall)
                Text("Issued To: ${details.subject}", style = MaterialTheme.typography.bodySmall)
                Text("Issued By: ${details.issuer}", style = MaterialTheme.typography.bodySmall)
                Text("Valid Until: ${details.validUntil}", style = MaterialTheme.typography.bodySmall)
                Text("SHA-256 Fingerprint:", style = MaterialTheme.typography.bodySmall)
                Text(
                    details.fingerprint,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )

                if (details.oldFingerprint != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Previous Fingerprint:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Text(
                        details.oldFingerprint,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onAccept) {
                Text("Trust")
            }
        },
        dismissButton = {
            Button(
                onClick = onReject,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Reject")
            }
        }
    )
}
