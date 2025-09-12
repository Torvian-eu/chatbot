# Revised Inline Dialog Architecture: State-Only Classes with Lambda Functions

### Core Principle
Dialog classes should contain **only state and lambda functions**, following the same pattern as the existing `SessionListDialogState` sealed classes. Use `null` StateFlow values to represent invisible state, eliminating the need for `isVisible` properties.

### New Group Dialog Implementation

#### State Class
```kotlin
data class NewGroupDialogState(
    val isSubmitting: Boolean = false,
    val groupNameInput: String = "",
    val serverError: String? = null,
    val hasUserInteracted: Boolean = false, // Track if user has focused/edited the field
    val showValidationErrors: Boolean = false, // Show errors after submit attempt or field blur
    
    // Lambda functions for actions (injected by ViewModel)
    val onGroupNameChange: (String) -> Unit,
    val onGroupNameFocused: () -> Unit, // Called when field gains focus
    val onGroupNameBlurred: () -> Unit, // Called when field loses focus
    val onSubmit: () -> Unit,
    val onCancel: () -> Unit
) {
    
    // Pure validation logic (no dependencies)
    fun validate(): DialogValidation {
        val trimmedName = groupNameInput.trim()
        return when {
            trimmedName.isBlank() -> DialogValidation(false, "Group name cannot be empty")
            trimmedName.length > 50 -> DialogValidation(false, "Group name too long (max 50 characters)")
            else -> DialogValidation(true)
        }
    }
    
    // Computed properties for UI convenience
    val isSubmitEnabled: Boolean
        get() = validate().isValid && !isSubmitting
    
    // Only show validation errors if user has interacted and we should show errors
    val shouldShowValidationError: Boolean
        get() = !validate().isValid && showValidationErrors
}

data class DialogValidation(
    val isValid: Boolean,
    val errorMessage: String? = null
)
```

#### Group Rename Dialog State
```kotlin
data class GroupRenameDialogState(
    val isSubmitting: Boolean = false,
    val editingGroup: ChatGroup,
    val groupNameInput: String = "",
    val serverError: String? = null,
    val hasUserInteracted: Boolean = false, // Track if user has focused/edited the field
    val showValidationErrors: Boolean = false, // Show errors after submit attempt or field blur
    
    // Lambda functions for actions
    val onGroupNameChange: (String) -> Unit,
    val onGroupNameFocused: () -> Unit,
    val onGroupNameBlurred: () -> Unit,
    val onSubmit: () -> Unit,
    val onCancel: () -> Unit
) {
    
    fun validate(): DialogValidation {
        val trimmedName = groupNameInput.trim()
        val originalName = editingGroup.name
        
        return when {
            trimmedName.isBlank() -> DialogValidation(false, "Group name cannot be empty")
            trimmedName.length > 50 -> DialogValidation(false, "Group name too long (max 50 characters)")
            trimmedName == originalName -> DialogValidation(false, "Name unchanged")
            else -> DialogValidation(true)
        }
    }
    
    val isSubmitEnabled: Boolean
        get() = validate().isValid && !isSubmitting
    
    val shouldShowValidationError: Boolean
        get() = !validate().isValid && showValidationErrors
}
```

### Updated ViewModel Implementation

```kotlin
class SessionListViewModel(
    private val sessionRepository: SessionRepository,
    private val groupRepository: GroupRepository,
    private val eventBus: EventBus,
    private val errorNotifier: ErrorNotifier,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : ViewModel() {

    // --- Inline Dialog States (null = invisible) ---
    private val _newGroupDialogState = MutableStateFlow<NewGroupDialogState?>(null)
    val newGroupDialogState: StateFlow<NewGroupDialogState?> = _newGroupDialogState.asStateFlow()
    
    private val _groupRenameDialogState = MutableStateFlow<GroupRenameDialogState?>(null)
    val groupRenameDialogState: StateFlow<GroupRenameDialogState?> = _groupRenameDialogState.asStateFlow()
    
    // --- Public API Methods ---
    
    fun showNewGroupDialog() {
        _newGroupDialogState.value = NewGroupDialogState(
            isSubmitting = false,
            groupNameInput = "",
            serverError = null,
            onGroupNameChange = { name -> updateNewGroupDialogName(name) },
            onGroupNameFocused = { onNewGroupDialogFocus() },
            onGroupNameBlurred = { onNewGroupDialogBlur() },
            onSubmit = { submitNewGroup() },
            onCancel = { hideNewGroupDialog() }
        )
    }
    
    fun showGroupRenameDialog(group: ChatGroup) {
        _groupRenameDialogState.value = GroupRenameDialogState(
            isSubmitting = false,
            editingGroup = group,
            groupNameInput = group.name,
            serverError = null,
            onGroupNameChange = { name -> updateGroupRenameDialogName(name) },
            onGroupNameFocused = { onGroupRenameDialogFocus() },
            onGroupNameBlurred = { onGroupRenameDialogBlur() },
            onSubmit = { submitGroupRename() },
            onCancel = { hideGroupRenameDialog() }
        )
    }
    
    // --- Private Implementation Methods ---
    
    private fun updateNewGroupDialogName(name: String) {
        _newGroupDialogState.value?.let { currentState ->
            _newGroupDialogState.value = currentState.copy(
                groupNameInput = name,
                serverError = null // Clear server error on input change
            )
        }
    }
    
    private fun onNewGroupDialogFocus() {
        _newGroupDialogState.value?.let { currentState ->
            _newGroupDialogState.value = currentState.copy(
                hasUserInteracted = true
            )
        }
    }
    
    private fun onNewGroupDialogBlur() {
        _newGroupDialogState.value?.let { currentState ->
            _newGroupDialogState.value = currentState.copy(
                showValidationErrors = currentState.hasUserInteracted
            )
        }
    }
    
    private fun hideNewGroupDialog() {
        _newGroupDialogState.value = null
    }
    
    private fun submitNewGroup() {
        val currentState = _newGroupDialogState.value ?: return
        val validation = currentState.validate()
        
        // Always show validation errors when user tries to submit
        _newGroupDialogState.value = currentState.copy(showValidationErrors = true)
        
        // Client-side validation - just return if invalid, UI will show validation error
        if (!validation.isValid) {
            return
        }
        
        _newGroupDialogState.value = currentState.copy(isSubmitting = true, serverError = null)
        
        viewModelScope.launch(uiDispatcher) {
            groupRepository.createGroup(CreateGroupRequest(currentState.groupNameInput.trim()))
                .fold(
                    ifLeft = { repositoryError ->
                        _newGroupDialogState.value?.let { state ->
                            _newGroupDialogState.value = state.copy(
                                isSubmitting = false, 
                                serverError = "Failed to create group: ${repositoryError.message}"
                            )
                        }
                        errorNotifier.repositoryError(repositoryError, "Failed to create new group")
                    },
                    ifRight = {
                        hideNewGroupDialog() // Success - hide dialog
                    }
                )
        }
    }
    
    private fun updateGroupRenameDialogName(name: String) {
        _groupRenameDialogState.value?.let { currentState ->
            _groupRenameDialogState.value = currentState.copy(
                groupNameInput = name,
                serverError = null
            )
        }
    }
    
    private fun onGroupRenameDialogFocus() {
        _groupRenameDialogState.value?.let { currentState ->
            _groupRenameDialogState.value = currentState.copy(
                hasUserInteracted = true
            )
        }
    }
    
    private fun onGroupRenameDialogBlur() {
        _groupRenameDialogState.value?.let { currentState ->
            _groupRenameDialogState.value = currentState.copy(
                showValidationErrors = currentState.hasUserInteracted
            )
        }
    }
    
    private fun hideGroupRenameDialog() {
        _groupRenameDialogState.value = null
    }
    
    private fun submitGroupRename() {
        val currentState = _groupRenameDialogState.value ?: return
        val validation = currentState.validate()
        val group = currentState.editingGroup
        
        // Always show validation errors when user tries to submit
        _groupRenameDialogState.value = currentState.copy(showValidationErrors = true)
        
        // Client-side validation - just return if invalid, UI will show validation error
        if (!validation.isValid) {
            return
        }
        
        _groupRenameDialogState.value = currentState.copy(isSubmitting = true, serverError = null)
        
        viewModelScope.launch(uiDispatcher) {
            groupRepository.renameGroup(group.id, RenameGroupRequest(currentState.groupNameInput.trim()))
                .fold(
                    ifLeft = { repositoryError ->
                        _groupRenameDialogState.value?.let { state ->
                            _groupRenameDialogState.value = state.copy(
                                isSubmitting = false, 
                                serverError = "Failed to rename group: ${repositoryError.message}"
                            )
                        }
                        errorNotifier.repositoryError(repositoryError, "Failed to rename group")
                    },
                    ifRight = {
                        hideGroupRenameDialog() // Success - hide dialog
                    }
                )
        }
    }
    
    // --- Removed Functions (replaced by dialog state management) ---
    // startCreatingNewGroup() -> showNewGroupDialog()
    // cancelCreatingNewGroup() -> hideNewGroupDialog() via onCancel
    // updateNewGroupNameInput() -> onGroupNameChange lambda
    // createNewGroup() -> onSubmit lambda  
    // startRenamingGroup() -> showGroupRenameDialog(group)
    // cancelRenamingGroup() -> hideGroupRenameDialog() via onCancel
    // updateEditingGroupNameInput() -> onGroupNameChange lambda
    // saveRenamedGroup() -> onSubmit lambda
}
```

### Clean UI Integration

```kotlin
@Composable
fun NewGroupInputSection(
    dialogState: NewGroupDialogState?
) {
    // null means dialog is not visible
    dialogState?.let { state ->
        val validation = state.validate()
        val focusManager = LocalFocusManager.current
        
        AnimatedVisibility(visible = true) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.groupNameInput,
                        onValueChange = state.onGroupNameChange,
                        label = { Text("New Group Name") },
                        singleLine = true,
                        enabled = !state.isSubmitting,
                        isError = state.shouldShowValidationError,
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    state.onGroupNameFocused()
                                } else if (focusState.hasFocus.not() && state.hasUserInteracted) {
                                    state.onGroupNameBlurred()
                                }
                            }
                    )
                    
                    Spacer(Modifier.width(8.dp))
                    
                    // Submit button
                    IconButton(
                        onClick = state.onSubmit,
                        enabled = state.isSubmitEnabled
                    ) {
                        if (state.isSubmitting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        } else {
                            Icon(Icons.Default.Check, contentDescription = "Create group")
                        }
                    }
                    
                    // Cancel button
                    IconButton(onClick = state.onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                }
                
                // Validation error display (client-side validation)
                if (state.shouldShowValidationError) {
                    Text(
                        text = validation.errorMessage ?: "Invalid input",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }
                
                // Server error display (server-side errors)
                state.serverError?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun GroupRenameInputSection(
    dialogState: GroupRenameDialogState?
) {
    // null means dialog is not visible
    dialogState?.let { state ->
        val validation = state.validate()
        val focusManager = LocalFocusManager.current
        
        AnimatedVisibility(visible = true) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.groupNameInput,
                        onValueChange = state.onGroupNameChange,
                        label = { Text("Group Name") },
                        singleLine = true,
                        enabled = !state.isSubmitting,
                        isError = state.shouldShowValidationError,
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    state.onGroupNameFocused()
                                } else if (focusState.hasFocus.not() && state.hasUserInteracted) {
                                    state.onGroupNameBlurred()
                                }
                            }
                    )
                    
                    Spacer(Modifier.width(8.dp))
                    
                    // Save button
                    IconButton(
                        onClick = state.onSubmit,
                        enabled = state.isSubmitEnabled
                    ) {
                        if (state.isSubmitting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        } else {
                            Icon(Icons.Default.Check, contentDescription = "Save changes")
                        }
                    }
                    
                    // Cancel button
                    IconButton(onClick = state.onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                }
                
                // Validation error display (client-side validation)
                if (state.shouldShowValidationError) {
                    Text(
                        text = validation.errorMessage ?: "Invalid input",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }
                
                // Server error display (server-side errors)
                state.serverError?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }
            }
        }
    }
}
```

### Updated SessionListActions Interface

```kotlin
interface SessionListActions {
    // Session selection
    fun onSessionSelected(sessionId: Long?)
    
    // Inline group dialogs (new approach)
    fun onShowNewGroupDialog()
    fun onShowGroupRenameDialog(group: ChatGroup)
    
    // Modal dialogs (existing approach)  
    fun onShowNewSessionDialog()
    fun onShowRenameSessionDialog(session: ChatSessionSummary)
    fun onShowDeleteSessionDialog(sessionId: Long)
    fun onShowAssignGroupDialog(session: ChatSessionSummary)
    fun onShowDeleteGroupDialog(groupId: Long)
    
    // Other actions
    fun onRetryLoadingSessions()
    
    // --- REMOVED (now handled by dialog state lambdas) ---
    // fun onStartCreatingNewGroup() 
    // fun onUpdateNewGroupNameInput(newText: String)
    // fun onCreateNewGroup()
    // fun onCancelCreatingNewGroup()
    // fun onStartRenamingGroup(group: ChatGroup)
    // fun onUpdateEditingGroupNameInput(newText: String)
    // fun onSaveRenamedGroup()
    // fun onCancelRenamingGroup()
}
```

## Architectural Benefits

### ✅ Clean Separation of Concerns
1. **Dialog State Classes**: Pure data classes with validation logic only
2. **ViewModel**: Contains business logic, repositories, and action handlers
3. **UI Components**: Consume state and call lambda functions

### ✅ Improved Architecture
1. **No Business Logic in UI-Shared Classes**: Dialog states are pure data
2. **Clear Boundaries**: UI layer only deals with state and callbacks
3. **Easy Testing**: Dialog state classes can be tested independently
4. **Consistent Pattern**: Follows existing sealed class dialog pattern

### ✅ Enhanced Features with Null Pattern
1. **Simpler State Management**: `null` clearly indicates invisible state
2. **Memory Efficiency**: Dialog objects only created when needed
3. **No isVisible Property**: Eliminates redundant boolean flag
4. **Cleaner UI Logic**: Simple null check instead of boolean check
5. **Built-in Validation**: Pure validation logic in state classes
6. **Loading States**: Proper submission state management
7. **Error Handling**: Both client-side validation and server errors

### ✅ Developer Experience
1. **Type Safety**: Strong typing for all state and actions
2. **Immutability**: All state changes through immutable copies
3. **Predictable Updates**: Clear state update patterns
4. **Easy Debugging**: All state changes are explicit
5. **Intuitive Pattern**: `null = invisible, object = visible` is very clear

## Migration Strategy

### Phase 1: Create Dialog State Classes
1. Implement `NewGroupDialogState` and `GroupRenameDialogState` without `isVisible` property
2. Add comprehensive unit tests for validation logic
3. Ensure all edge cases are handled

### Phase 2: Update ViewModel
1. Replace existing StateFlow properties with nullable dialog state instances
2. Implement show methods that create dialog objects with lambda functions
3. Implement hide methods that set state to null
4. Update state update methods to use null-safe operations
5. Maintain backward compatibility during transition

### Phase 3: Update UI Components
1. Modify existing compose components to use nullable dialog states
2. Use `dialogState?.let { }` pattern for conditional rendering
3. Remove direct calls to old ViewModel methods
4. Update `SessionListActions` interface
5. Test all UI interactions thoroughly

### Phase 4: Cleanup and Polish
1. Remove old StateFlow properties and methods from ViewModel
2. Add inline documentation for new null pattern
3. Consider applying pattern to other ViewModels
4. Update project documentation

## Comparison with Existing Dialog Pattern

### Similarity to SessionListDialogState
The new approach closely mirrors the existing modal dialog pattern but uses nullable StateFlow:

```kotlin
// Existing modal dialog pattern (sealed class)
sealed class SessionListDialogState {
    object None : SessionListDialogState()
    data class NewSession(
        val sessionNameInput: String = "",
        val onNameInputChange: (String) -> Unit,
        val onCreateSession: (String) -> Unit,
        val onDismiss: () -> Unit
    ) : SessionListDialogState()
}

// New inline dialog pattern (nullable StateFlow)
// null = invisible, object = visible
data class NewGroupDialogState(
    val groupNameInput: String = "",
    val onGroupNameChange: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onCancel: () -> Unit
)
```

**Key Improvements:**
- **Null Pattern**: More intuitive than `None` object or `isVisible` boolean
- **Memory Efficiency**: Objects only created when dialogs are shown
- **Enhanced validation**: Built-in validation with error messages
- **Computed properties**: UI convenience properties like `isSubmitEnabled`

## Risk Assessment

### Low Risk
- **Architecture**: Follows established patterns in the codebase
- **Migration**: Can be implemented incrementally without breaking changes
- **Performance**: Better memory usage with on-demand object creation
- **Maintainability**: Cleaner separation of concerns

### Medium Risk  
- **Development Time**: Estimated 1-2 developer days for complete migration
- **Testing**: Need to update existing UI tests for null checks
- **Learning Curve**: Team needs to understand new null pattern

### Negligible Risk
- **Over-Engineering**: Simple data classes with clear purpose
- **Backwards Compatibility**: Easy to maintain during migration
- **Null Safety**: Kotlin's null safety prevents null pointer issues

## Conclusion

The revised approach with null StateFlow pattern addresses all architectural concerns and provides additional benefits:

1. **✅ Pure State Classes**: No business logic or dependencies in UI-shared classes
2. **✅ Lambda Function Pattern**: Follows existing dialog state approach
3. **✅ Clean Architecture**: Clear separation between UI, business, and data layers
4. **✅ Enhanced Features**: Better validation, error handling, and loading states
5. **✅ Consistent Pattern**: Aligns with existing codebase conventions
6. **✅ Memory Efficiency**: Objects created only when needed
7. **✅ Intuitive Design**: Null pattern is more intuitive than boolean flags

**Recommendation**: Implement the nullable state-only dialog classes with lambda functions. This provides all the benefits of organized inline dialog management while maintaining clean architectural boundaries, following established patterns, and improving memory efficiency.

**Estimated Development Time**: 1-2 developer days
**Architecture Quality**: High - follows clean architecture principles
**Future Extensibility**: Excellent - easy to add new inline dialogs with consistent pattern
