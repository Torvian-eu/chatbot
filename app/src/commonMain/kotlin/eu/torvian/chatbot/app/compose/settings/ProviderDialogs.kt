package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ConfigDropdown
import eu.torvian.chatbot.app.compose.common.ConfigTextField
import eu.torvian.chatbot.app.compose.common.CredentialField
import eu.torvian.chatbot.app.domain.contracts.EditProviderFormState
import eu.torvian.chatbot.app.domain.contracts.NewProviderFormState
import eu.torvian.chatbot.common.models.LLMProvider
import eu.torvian.chatbot.common.models.LLMProviderType

/**
 * Modal dialog for adding a new LLM provider (E4.S8).
 * Includes form validation and proper error handling.
 */
@Composable
fun AddProviderDialog(
    formState: NewProviderFormState,
    onNameChange: (String) -> Unit,
    onTypeChange: (LLMProviderType) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onCredentialChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Add New Provider",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Provider Name
                ConfigTextField(
                    value = formState.name,
                    onValueChange = onNameChange,
                    label = "Provider Name",
                    placeholder = "e.g., OpenAI Production",
                    isError = formState.errorMessage?.contains("name", ignoreCase = true) ?: false
                )

                // Provider Type
                ConfigDropdown(
                    selectedItem = formState.type,
                    onItemSelected = onTypeChange,
                    items = LLMProviderType.entries,
                    label = "Provider Type",
                    itemText = { it.name }
                )

                // Base URL
                ConfigTextField(
                    value = formState.baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = "Base URL",
                    placeholder = "e.g., https://api.openai.com/v1",
                    isError = formState.errorMessage?.contains("url", ignoreCase = true) ?: false
                )

                // Description
                ConfigTextField(
                    value = formState.description,
                    onValueChange = onDescriptionChange,
                    label = "Description",
                    placeholder = "Optional description",
                    singleLine = false
                )

                // API Key (Credential)
                CredentialField(
                    value = formState.credential,
                    onValueChange = onCredentialChange,
                    label = "API Key",
                    placeholder = "Enter API key (optional for local providers)"
                )

                // Error message display
                formState.errorMessage?.let { errorMessage ->
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm
            ) {
                Text("Add Provider")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss
            ) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Modal dialog for editing an existing LLM provider (E4.S10, E4.S12).
 * Includes both general provider details and credential management.
 */
@Composable
fun EditProviderDialog(
    provider: LLMProvider,
    formState: EditProviderFormState,
    credentialUpdateLoading: Boolean,
    onNameChange: (String) -> Unit,
    onTypeChange: (LLMProviderType) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onNewCredentialInputChange: (String) -> Unit,
    onUpdateProvider: () -> Unit,
    onUpdateCredential: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Edit Provider: ${provider.name}",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Provider Name
                ConfigTextField(
                    value = formState.name,
                    onValueChange = onNameChange,
                    label = "Provider Name",
                    isError = formState.errorMessage?.contains("name", ignoreCase = true) ?: false
                )

                // Provider Type
                ConfigDropdown(
                    selectedItem = formState.type,
                    onItemSelected = onTypeChange,
                    items = LLMProviderType.entries,
                    label = "Provider Type",
                    itemText = { it.name }
                )

                // Base URL
                ConfigTextField(
                    value = formState.baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = "Base URL",
                    isError = formState.errorMessage?.contains("url", ignoreCase = true) ?: false
                )

                // Description
                ConfigTextField(
                    value = formState.description,
                    onValueChange = onDescriptionChange,
                    label = "Description",
                    singleLine = false
                )

                // Credential Management Section (E4.S12)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "API Key Management",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Current API Key Status (E5.S4)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Current Status:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (provider.apiKeyId != null) {
                                    Icon(
                                        imageVector = Icons.Default.Key,
                                        contentDescription = "API Key Configured",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                    Text(
                                        text = "Configured",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Text(
                                        text = "Not configured",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }

                        // New API Key Input
                        CredentialField(
                            value = formState.newCredentialInput,
                            onValueChange = onNewCredentialInputChange,
                            label = "New API Key",
                            placeholder = "Enter new API key to update (leave empty to keep current)"
                        )

                        // Update Credential Button
                        Button(
                            onClick = onUpdateCredential,
                            enabled = !credentialUpdateLoading && formState.newCredentialInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (credentialUpdateLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = if (credentialUpdateLoading) "Updating..." else "Update API Key"
                            )
                        }
                    }
                }

                // Error message display
                formState.errorMessage?.let { errorMessage ->
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onUpdateProvider
            ) {
                Text("Update Provider")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss
            ) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Confirmation dialog for deleting a provider (E4.S11).
 */
@Composable
fun DeleteProviderConfirmationDialog(
    provider: LLMProvider,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Delete Provider",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Text(
                text = "Are you sure you want to delete the provider \"${provider.name}\"?\n\n" +
                        "This action cannot be undone and will remove all associated configurations.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss
            ) {
                Text("Cancel")
            }
        }
    )
}
