## Architecture Guide: Advanced Inline Dialogs with Form State Management (Revised & Complete)

### 1. Introduction & Goal

This document outlines a new, standardized architecture for creating inline dialogs and forms within our application. The previous approach of using multiple, scattered `StateFlow`s in the ViewModel for a single dialog does not scale well and can lead to inconsistent states.

The goal of this new architecture is to create a pattern that is:
*   **Scalable:** Easily handles forms with one or many fields.
*   **Reusable:** Common form logic and UI components can be shared.
*   **Robust:** Eliminates inconsistent states through a single source of truth.
*   **Testable:** Clearly separates state, business logic, and UI for easy unit testing.
*   **User-Friendly:** Provides a superior user experience with clear, actionable feedback.

### 2. Core Principles

1.  **Single, Nullable State Object:** Each dialog is represented by a single, nullable `StateFlow` in the ViewModel. `null` means the dialog is hidden; a non-null object means it's visible.
2.  **State-Only Data Classes:** The dialog's state is held in an immutable `data class`. This class contains all the necessary data for the UI to render itself.
3.  **ViewModel as State Producer:** The ViewModel contains the business logic and validation rules. It responds to UI events and produces new, updated state objects.
4.  **Composable UI as State Consumer:** The UI is "dumb." It receives the state object, renders the UI accordingly, and calls the provided lambdas on user interaction.

### 3. The Building Blocks: A Concrete Example

We will use a hypothetical "Create New Server" dialog with two fields (`serverName`, `serverAddress`) as our reference implementation.

#### Part 1: `FormFieldState` — The Reusable Field Atom

This generic class represents the state of *any single field* in a form.

```kotlin
// file: app/src/commonMain/kotlin/eu/torvian/chatbot/app/viewmodel/forms/FormFieldState.kt
package eu.torvian.chatbot.app.viewmodel.forms

/**
 * Represents the complete state of a single input field in a form.
 *
 * @param T The type of the field's value (e.g., String).
 * @property value The current value of the field.
 * @property hasBeenInteracted True if the user has focused and then blurred the field.
 * @property validationError An optional error message from validation. Null if valid.
 */
data class FormFieldState<T>(
    val value: T,
    val hasBeenInteracted: Boolean = false,
    val validationError: String? = null
)
```

#### Part 2: `NewServerDialogState` — The Form Container

This state class is specific to our dialog. It is composed of `FormFieldState` objects and contains the global state for the entire dialog.

```kotlin
// file: app/src/commonMain/kotlin/eu/torvian/chatbot/app/viewmodel/forms/NewServerDialogState.kt
package eu.torvian.chatbot.app.viewmodel.forms

data class NewServerDialogState(
    // State for each field using our reusable class
    val serverName: FormFieldState<String> = FormFieldState(""),
    val serverAddress: FormFieldState<String> = FormFieldState(""),

    // Global state for the entire dialog
    val isSubmitting: Boolean = false,
    val serverError: String? = null, // For errors from the backend
    val submitAttempted: Boolean = false, // True if the user has clicked the submit button

    // Lambdas for the UI to call on user interaction
    val onServerNameChange: (String) -> Unit,
    val onServerAddressChange: (String) -> Unit,
    val onServerNameBlurred: () -> Unit,
    val onServerAddressBlurred: () -> Unit,
    val onSubmit: () -> Unit,
    val onCancel: () -> Unit
) {
    // Computed properties to simplify UI logic
    
    /** True if all fields are valid. */
    val isFormValid: Boolean
        get() = serverName.validationError == null && serverAddress.validationError == null
    
    /** Determines if the server name field should display an error. */
    val serverNameShouldShowError: Boolean
        get() = (serverName.hasBeenInteracted || submitAttempted) && serverName.validationError != null

    /** Determines if the server address field should display an error. */
    val serverAddressShouldShowError: Boolean
        get() = (serverAddress.hasBeenInteracted || submitAttempted) && serverAddress.validationError != null
}
```

#### Part 3: ViewModel Implementation — The Orchestrator

The ViewModel houses the validation rules and orchestrates state changes.

```kotlin
// file: app/src/commonMain/kotlin/eu/torvian/chatbot/app/viewmodel/SomeViewModel.kt
package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.viewmodel.forms.FormFieldState
import eu.torvian.chatbot.app.viewmodel.forms.NewServerDialogState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SomeViewModel(/* private val serverRepository: ServerRepository */) : ViewModel() {

    private val _newServerDialogState = MutableStateFlow<NewServerDialogState?>(null)
    val newServerDialogState: StateFlow<NewServerDialogState?> = _newServerDialogState.asStateFlow()

    // --- Public API to show the dialog ---
    fun showNewServerDialog() {
        _newServerDialogState.value = NewServerDialogState(
            serverName = FormFieldState("", validationError = validateName("")), // Pre-validate
            serverAddress = FormFieldState("", validationError = validateAddress("")), // Pre-validate
            onServerNameChange = ::updateServerName,
            onServerAddressChange = ::updateServerAddress,
            onServerNameBlurred = ::onServerNameBlurred,
            onServerAddressBlurred = ::onServerAddressBlurred,
            onSubmit = ::submitNewServer,
            onCancel = { _newServerDialogState.value = null }
        )
    }
    
    // --- Business Logic & Validation ---
    private fun validateName(name: String): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isBlank() -> "Server name cannot be empty"
            trimmed.length > 50 -> "Name is too long (max 50 characters)"
            else -> null // Valid
        }
    }

    private fun validateAddress(address: String): String? {
        val trimmed = address.trim()
        return when {
            trimmed.isBlank() -> "Address cannot be empty"
            !trimmed.startsWith("http://") && !trimmed.startsWith("https://") -> "Must be a valid URL"
            else -> null // Valid
        }
    }
    
    // --- Private State Update Functions ---
    private fun updateServerName(name: String) {
        _newServerDialogState.update { state ->
            state?.copy(
                serverName = state.serverName.copy(
                    value = name, 
                    validationError = validateName(name)
                )
            )
        }
    }

    private fun updateServerAddress(address: String) {
        _newServerDialogState.update { state ->
            state?.copy(
                serverAddress = state.serverAddress.copy(
                    value = address,
                    validationError = validateAddress(address)
                )
            )
        }
    }

    private fun onServerNameBlurred() {
        _newServerDialogState.update { state ->
            state?.copy(serverName = state.serverName.copy(hasBeenInteracted = true))
        }
    }

    private fun onServerAddressBlurred() {
        _newServerDialogState.update { state ->
            state?.copy(serverAddress = state.serverAddress.copy(hasBeenInteracted = true))
        }
    }

    private fun submitNewServer() {
        val currentState = _newServerDialogState.value ?: return

        if (!currentState.isFormValid) {
            _newServerDialogState.value = currentState.copy(submitAttempted = true)
            return
        }
        
        _newServerDialogState.update { it?.copy(isSubmitting = true) }
        
        viewModelScope.launch {
            // val result = serverRepository.createServer(
            //     name = currentState.serverName.value.trim(),
            //     address = currentState.serverAddress.value.trim()
            // )
            // result.fold(
            //     ifLeft = { error -> /* Update state with serverError */ },
            //     ifRight = { /* Hide dialog on success */ }
            // )
        }
    }
}
```

#### Part 4: Composable Implementation — The Renderer

The UI is composed of a reusable `FormTextField` and a specific dialog composable.

```kotlin
// file: app/src/commonMain/kotlin/eu/torvian/chatbot/app/compose/forms/FormTextField.kt
package eu.torvian.chatbot.app.compose.forms

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import eu.torvian.chatbot.app.viewmodel.forms.FormFieldState

@Composable
fun FormTextField(
    modifier: Modifier = Modifier,
    fieldState: FormFieldState<String>,
    onValueChange: (String) -> Unit,
    onBlurred: () -> Unit,
    label: String,
    shouldShowError: Boolean
) {
    OutlinedTextField(
        value = fieldState.value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = shouldShowError,
        supportingText = {
            if (shouldShowError) { Text(fieldState.validationError ?: "") }
        },
        modifier = modifier.onFocusChanged { focusState ->
            // Only trigger onBlurred the first time the user leaves the field
            if (!focusState.isFocused && !fieldState.hasBeenInteracted) {
                onBlurred()
            }
        }
    )
}

// file: app/src/commonMain/kotlin/eu/torvian/chatbot/app/compose/dialogs/NewServerDialog.kt
package eu.torvian.chatbot.app.compose.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.forms.FormTextField
import eu.torvian.chatbot.app.viewmodel.forms.NewServerDialogState

@Composable
fun NewServerDialog(
    dialogState: NewServerDialogState?,
    onDismissRequest: () -> Unit
) {
    if (dialogState != null) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text("Create New Server") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormTextField(
                        fieldState = dialogState.serverName,
                        onValueChange = dialogState.onServerNameChange,
                        onBlurred = dialogState.onServerNameBlurred,
                        label = "Server Name",
                        shouldShowError = dialogState.serverNameShouldShowError
                    )
                    FormTextField(
                        fieldState = dialogState.serverAddress,
                        onValueChange = dialogState.onServerAddressChange,
                        onBlurred = dialogState.onServerAddressBlurred,
                        label = "Server Address",
                        shouldShowError = dialogState.serverAddressShouldShowError
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                Button(
                    onClick = dialogState.onSubmit,
                    enabled = !dialogState.isSubmitting
                ) {
                    if (dialogState.isSubmitting) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Text("Create")
                    }
                }
            }
        )
    }
}
```

### 5. Key Design Decision: The "Intelligent" Submit Button

A crucial part of this architecture is the user experience surrounding form submission. We explicitly **avoid disabling the submit button just because the form has validation errors.**

**Problem with Disabled Buttons:** When a submit button is disabled, the user often doesn't know *why*. They are blocked without any guidance, leading to frustration.

**Our Solution:** The submit button remains enabled (unless a network request is active). This encourages the user to click it when they believe they are finished. This action serves as a "Check my work" signal.

1.  User clicks "Submit" on a form with an empty, required field.
2.  The `submitNewServer()` function in the ViewModel is triggered.
3.  It detects the form is invalid (`isFormValid` is `false`).
4.  It updates the state, setting `submitAttempted = true`.
5.  The UI recomposes. The `...ShouldShowError` properties now evaluate to `true` for any invalid fields.
6.  The user sees clear, inline error messages and knows exactly what to fix.

This pattern transforms the submit button from a frustrating gatekeeper into a helpful guide, ensuring the user is never stuck.

### 6. Summary of Benefits

*   **Clear Separation of Concerns:** State lives in data classes, logic in the ViewModel, and rendering in Composables.
*   **Superior User Experience:** Guarantees users are never stuck with a disabled button and no feedback. The form actively guides them to a valid state.
*   **High Reusability:** `FormFieldState` and `FormTextField` can be used for any form in the app.
*   **Single Source of Truth:** The entire state of the dialog is in one immutable object, preventing bugs.
*   **Easy to Test:** You can unit test validation rules in the ViewModel and the logic in the state classes without any UI framework.