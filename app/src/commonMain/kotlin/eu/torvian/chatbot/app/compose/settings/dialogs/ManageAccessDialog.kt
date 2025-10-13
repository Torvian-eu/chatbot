package eu.torvian.chatbot.app.compose.settings.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.domain.contracts.GrantAccessFormState
import eu.torvian.chatbot.common.models.api.access.ResourceAccessDetails
import eu.torvian.chatbot.common.models.api.access.ResourceAccessInfo
import eu.torvian.chatbot.common.models.user.UserGroup

/**
 * Dialog for managing group access to a resource (provider, model, or settings).
 */
@Composable
fun ManageAccessDialog(
    resourceName: String,
    accessDetails: ResourceAccessDetails,
    availableGroups: List<UserGroup>,
    showGrantDialog: Boolean,
    grantAccessForm: GrantAccessFormState,
    onOpenGrantDialog: () -> Unit,
    onCloseGrantDialog: () -> Unit,
    onUpdateGrantForm: (GrantAccessFormState) -> Unit,
    onConfirmGrant: (Long, String) -> Unit,
    onRevokeAccess: (Long, String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Access: $resourceName") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Owner section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Owner",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = accessDetails.owner?.username ?: "Unknown",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // Access list section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Group Access", style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = onOpenGrantDialog) {
                        Icon(Icons.Default.Add, contentDescription = "Grant Access")
                    }
                }

                if (accessDetails.accessList.isEmpty()) {
                    Text(
                        text = "No groups have access to this resource",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            accessDetails.accessList,
                            key = { "${it.groupId}_${it.accessMode}" }
                        ) { accessInfo ->
                            AccessListItem(
                                accessInfo = accessInfo,
                                onRevoke = { onRevokeAccess(accessInfo.groupId, accessInfo.accessMode) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )

    if (showGrantDialog) {
        GrantAccessDialog(
            availableGroups = availableGroups,
            grantAccessForm = grantAccessForm,
            onUpdateForm = onUpdateGrantForm,
            onConfirm = { groupId, accessMode ->
                onConfirmGrant(groupId, accessMode)
            },
            onDismiss = onCloseGrantDialog
        )
    }
}

/**
 * Individual access list item showing a group with an access mode.
 */
@Composable
private fun AccessListItem(
    accessInfo: ResourceAccessInfo,
    onRevoke: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(accessInfo.groupName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Access: ${accessInfo.accessMode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onRevoke) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Revoke Access",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Dialog for granting access to a group.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GrantAccessDialog(
    availableGroups: List<UserGroup>,
    grantAccessForm: GrantAccessFormState,
    onUpdateForm: (GrantAccessFormState) -> Unit,
    onConfirm: (Long, String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Grant Access") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Group selection
                Text("Select Group", style = MaterialTheme.typography.labelMedium)

                ExposedDropdownMenuBox(
                    expanded = grantAccessForm.dropdownExpanded,
                    onExpandedChange = { onUpdateForm(grantAccessForm.copy(dropdownExpanded = !grantAccessForm.dropdownExpanded)) }
                ) {
                    OutlinedTextField(
                        value = availableGroups.find { it.id == grantAccessForm.selectedGroupId }?.name ?: "Select a group",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = grantAccessForm.dropdownExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )

                    ExposedDropdownMenu(
                        expanded = grantAccessForm.dropdownExpanded,
                        onDismissRequest = { onUpdateForm(grantAccessForm.copy(dropdownExpanded = false)) }
                    ) {
                        availableGroups.forEach { group ->
                            DropdownMenuItem(
                                text = { Text(group.name) },
                                onClick = {
                                    onUpdateForm(grantAccessForm.copy(selectedGroupId = group.id, dropdownExpanded = false))
                                }
                            )
                        }
                    }
                }

                // Access mode selection
                Text("Access Mode", style = MaterialTheme.typography.labelMedium)

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    grantAccessForm.availableAccessModes.forEach { mode ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = grantAccessForm.selectedAccessMode == mode,
                                onClick = { onUpdateForm(grantAccessForm.copy(selectedAccessMode = mode)) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    mode.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = when (mode) {
                                        "read" -> "View only"
                                        "write" -> "View and modify"
                                        "manage" -> "Full control including sharing"
                                        else -> ""
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    grantAccessForm.selectedGroupId?.let { groupId ->
                        onConfirm(groupId, grantAccessForm.selectedAccessMode)
                    }
                },
                enabled = grantAccessForm.selectedGroupId != null
            ) {
                Text("Grant")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
