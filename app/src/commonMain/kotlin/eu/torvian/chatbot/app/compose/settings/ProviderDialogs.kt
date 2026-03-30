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
import eu.torvian.chatbot.app.domain.contracts.ProviderFormState
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType

private fun providerTypeDisplayName(type: LLMProviderType): String = when (type) {
    LLMProviderType.ANTHROPIC -> "${type.name} (not supported yet)"
    else -> type.name
}

private fun isProviderTypeSupportedInUi(type: LLMProviderType): Boolean =
    type != LLMProviderType.ANTHROPIC

/**
 * Modal dialog for adding a new LLM provider (E4.S8).
 * Includes form validation and proper error handling.
 */
@Composable
fun AddProviderDialog(
    formState: ProviderFormState,
    connectionTestLoading: Boolean,
    onNameChange: (String) -> Unit,
    onTypeChange: (LLMProviderType) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onCredentialChange: (String) -> Unit,
    onTestConnection: () -> Unit,
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
                    placeholder = "e.g., OpenAI, Anthropic",
                    isError = formState.errorMessage?.contains("name", ignoreCase = true) ?: false
                )

                // Provider Type
                ConfigDropdown(
                    selectedItem = formState.type,
                    onItemSelected = onTypeChange,
                    items = LLMProviderType.entries,
                    label = "Provider Type",
                    itemText = ::providerTypeDisplayName,
                    itemEnabled = ::isProviderTypeSupportedInUi
                )

                // Base URL
                ConfigTextField(
                    value = formState.baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = "Base URL",
                    placeholder = "https://api.example.com/v1",
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

                // API Key (Optional for new providers)
                CredentialField(
                    value = formState.credential,
                    onValueChange = onCredentialChange,
                    label = "API Key (Optional)",
                    placeholder = "Enter API key if available"
                )

                // Error message display
                formState.errorMessage?.let { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onTestConnection,
                    enabled = !connectionTestLoading && formState.baseUrl.isNotBlank()
                ) {
                    if (connectionTestLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Testing...")
                    } else {
                        Text("Test Connection")
                    }
                }
                Button(onClick = onConfirm, enabled = !connectionTestLoading) {
                    Text("Add Provider")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Modal dialog for editing an existing LLM provider (E4.S10 & E4.S12).
 * Supports both general details editing and credential management.
 */
@Composable
fun EditProviderDialog(
    originalProviderName: String,
    formState: ProviderFormState,
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
                text = "Edit Provider: $originalProviderName",
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
                    itemText = ::providerTypeDisplayName,
                    itemEnabled = ::isProviderTypeSupportedInUi
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

                        // New credential input
                        CredentialField(
                            value = formState.credential,
                            onValueChange = onNewCredentialInputChange,
                            label = "New API Key",
                            placeholder = "Enter new API key to update"
                        )

                        // Update credential button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = onUpdateCredential,
                                enabled = !credentialUpdateLoading && formState.credential.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (credentialUpdateLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Key,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Update API Key")
                            }
                        }
                    }
                }

                // Error message display
                formState.errorMessage?.let { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onUpdateProvider,
                enabled = !credentialUpdateLoading
            ) {
                Text("Update Provider")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
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
