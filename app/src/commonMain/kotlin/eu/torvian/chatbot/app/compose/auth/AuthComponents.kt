package eu.torvian.chatbot.app.compose.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.security.PasswordValidationConfig
import eu.torvian.chatbot.common.security.UsernameValidationConfig

/**
 * Reusable text field component for authentication forms.
 */
@Composable
fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    errorMessage: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    enabled: Boolean = true,
    singleLine: Boolean = true
) {
    val focusManager = LocalFocusManager.current

    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            isError = isError,
            enabled = enabled,
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = imeAction
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                onDone = { onImeAction() }
            ),
            modifier = Modifier.fillMaxWidth()
        )

        if (isError && errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    }
}

/**
 * Password text field with visibility toggle.
 */
@Composable
fun PasswordTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    errorMessage: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    enabled: Boolean = true
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            isError = isError,
            enabled = enabled,
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = imeAction
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                onDone = { onImeAction() }
            ),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        if (isError && errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    }
}

/**
 * Loading button component for authentication actions.
 */
@Composable
fun AuthButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = modifier.fillMaxWidth()
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text)
    }
}

/**
 * Error message display component.
 */
@Composable
fun ErrorMessage(
    message: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp)
        )
    }
}

/**
 * Success message display component.
 *
 * @param message The success message to display.
 * @param modifier Optional modifier for the card.
 */
@Composable
fun SuccessMessage(
    message: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp)
        )
    }
}

/**
 * Displays password requirements as a card with a bulleted list.
 *
 * Each requirement bullet is only shown when the corresponding rule is enabled
 * in the provided [config]. This allows the hint to dynamically reflect the
 * actual validation policy.
 *
 * @param config The password validation configuration that determines which requirements to show
 * @param modifier Optional modifier for the card
 */
@Composable
fun PasswordRequirementsHint(
    config: PasswordValidationConfig,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Password Requirements:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "\u2022 At least ${config.minLength} characters",
                style = MaterialTheme.typography.bodySmall
            )
            if (config.requireUppercase && config.requireLowercase) {
                Text(
                    text = "\u2022 Contains uppercase and lowercase letters",
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (config.requireUppercase) {
                Text(
                    text = "\u2022 Contains uppercase letters",
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (config.requireLowercase) {
                Text(
                    text = "\u2022 Contains lowercase letters",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (config.requireDigit) {
                Text(
                    text = "\u2022 Contains at least one number",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (config.requireSpecialChar) {
                Text(
                    text = "\u2022 Contains at least one special character",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * Displays username requirements as a card with a bulleted list.
 *
 * Each requirement bullet is only shown when the corresponding rule is enabled
 * in the provided [config]. This allows the hint to dynamically reflect the
 * actual validation policy.
 *
 * @param config The username validation configuration that determines which requirements to show
 * @param modifier Optional modifier for the card
 */
@Composable
fun UsernameRequirementsHint(
    config: UsernameValidationConfig,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Username Requirements:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "\u2022 At least ${config.minLength} characters",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "\u2022 No more than ${config.maxLength} characters",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "\u2022 Only contains ${describeAllowedPattern(config.allowedRegexPattern)}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * Converts a regex pattern string into a user-friendly description of allowed characters.
 *
 * Recognizes common patterns and provides human-readable descriptions.
 * Falls back to a generic message if the pattern is not recognized.
 *
 * @param pattern The regex pattern string from [UsernameValidationConfig.allowedRegexPattern]
 * @return A user-friendly description of allowed characters
 */
private fun describeAllowedPattern(pattern: String): String {
    return when (pattern) {
        "^[a-zA-Z0-9_-]+\$" -> "letters, numbers, hyphens, and underscores"
        "^[a-zA-Z0-9]+\$" -> "letters and numbers"
        "^[a-zA-Z]+\$" -> "letters"
        "^[0-9]+\$" -> "numbers"
        else -> "allowed characters based on server policy"
    }
}

/**
 * Displays a warning card for restricted sessions with a customizable message.
 *
 * @param message The message to display in the warning card.
 * @param modifier Optional modifier for the card.
 */
@Composable
fun RestrictedSessionWarning(
    message: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Restricted Session",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}
