### **Development Plan: Implementing Secure Client-Side Token Storage**

**Version:** 1.0
**Date:** October 26, 2023
**Author:** [Your Name/Team Lead]

#### **1. Introduction & Goals**

This document outlines the plan to enhance the security of our application's client-side token storage. Currently, sensitive authentication tokens (access and refresh tokens) are stored in a way that, while functional, could be vulnerable to sophisticated Cross-Site Scripting (XSS) attacks.

The primary goal is to implement a **Defense in Depth** strategy for our browser-based platform (`wasmJs`) by encrypting tokens at rest. This will be achieved using the browser's native, secure **Web Crypto API**. This approach prevents the most common token-theft attacks and protects our master encryption key from being extracted, even if an XSS vulnerability were to be found in the future.

**High-Level Goals:**
*   Prevent passive theft of authentication tokens from browser storage.
*   Securely manage and persist the master encryption key using browser-native security features.
*   Ensure the cryptographic implementation is robust, modern, and platform-specific.
*   Maintain the existing multiplatform architecture by adapting all targets (`jvm`, `android`, `wasmJs`) to the new cryptographic interface.

#### **2. Key Technical Concepts**

To understand the implementation, the team should be familiar with these concepts:

1.  **Envelope Encryption:** A two-layered encryption strategy.
    *   **Data Encryption Key (DEK):** A unique, randomly generated key used to encrypt the actual data (our tokens). A new DEK is generated for each new set of tokens.
    *   **Key Encryption Key (KEK):** A static, long-term master key used only to encrypt (or "wrap") the DEK. Our `EncryptionConfig` provides this key.

2.  **Web Crypto API:** A low-level browser API for performing cryptographic operations. We will use it for all encryption, decryption, key generation, and key wrapping/unwrapping.

3.  **Non-Extractable Keys:** A feature of the Web Crypto API. We will import our KEK into the browser's crypto engine with the `extractable: false` flag. This creates an opaque `CryptoKey` object. Our application code can get a *handle* to this key to perform operations, but it can **never read the raw key material back**. This is the core of our security model.

4.  **IndexedDB:** A browser-based database. We will use it to store the non-extractable `CryptoKey` handle for our KEK, allowing it to persist between user sessions securely.

5.  **Asynchronous Operations:** The Web Crypto API is asynchronous (it returns Promises in JavaScript). This requires our shared `CryptoProvider` interface to use `suspend` functions, which will have a cascading effect on our existing synchronous JVM/Android implementations.

#### **3. Implementation Steps**

The work is divided into four main tasks. They should be completed in order.

##### **Task 1: Make the Core Cryptographic Interface Asynchronous**

This is a foundational change required by the Web Crypto API.

*   **File to Modify:** `common/src/commonMain/kotlin/eu/torvian/chatbot/common/security/CryptoProvider.kt`
*   **Action:** Modify the method signatures in the `CryptoProvider` interface to be `suspend` functions. `getKeyVersion()` can remain synchronous.

**Example Change:**
```kotlin
// Before
fun generateDEK(): Either<CryptoError, String>

// After
suspend fun generateDEK(): Either<CryptoError, String>
```
*   **Apply this change to:** `generateDEK`, `encryptData`, `decryptData`, `wrapDEK`, and `unwrapDEK`.

##### **Task 2: Adapt Existing JVM & Android Implementations**

The change in Task 1 will cause compilation errors in our existing `AESCryptoProvider`. We must adapt it to the new `suspend` interface.

*   **File to Modify:** The file containing `AESCryptoProvider` (e.g., in `common/src/jvmMain` or a similar JVM/Android source set).
*   **Action:** Wrap the body of each overridden `suspend` function with `withContext(Dispatchers.IO) { ... }`. This moves the blocking cryptographic operations off the main thread in a coroutine-idiomatic way.

**Example Change:**
```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ... inside AESCryptoProvider class

override suspend fun wrapDEK(dek: String): Either<CryptoError, String> = withContext(Dispatchers.IO) {
    // The original, synchronous code from the method body goes here.
    either {
        catch({
            // ... existing logic ...
        }) { e: Exception ->
            raise(CryptoError.EncryptionError("Failed to wrap DEK: ${e.message}"))
        }
    }
}
```
*   **Apply this wrapper to all newly `suspend` methods in `AESCryptoProvider`.**

##### **Task 3: Implement the Secure Browser `CryptoProvider`**

This task involves creating the new, secure implementation for the `wasmJs` target.

*   **Action 3a: Create JS Interop Files**
    *   Create a new directory: `app/src/wasmJsMain/kotlin/eu/torvian/chatbot/app/external/`.
    *   Create `WebCrypto.kt` in this directory to define `external` interfaces for the browser's `window.crypto.subtle` API and `CryptoKey`.
    *   Create `IndexedDB.kt` in the same directory to define a simple, promise-based wrapper for getting and putting data into IndexedDB.
    *   *(Refer to the provided source code for the exact content of these files).*

*   **Action 3b: Implement `WasmJsWebCryptoProvider`**
    *   Create a new file: `app/src/wasmJsMain/kotlin/eu/torvian/chatbot/app/security/WasmJsWebCryptoProvider.kt`.
    *   Create the `WasmJsWebCryptoProvider` class, implementing the `CryptoProvider` interface.
    *   **Key Responsibilities of this class:**
        *   Define the `AES-GCM` (for data) and `AES-KW` (for keys) algorithm configurations.
        *   Implement a `getKek` function that:
            1.  Tries to load the non-extractable `CryptoKey` from IndexedDB.
            2.  If not found, imports the raw key from `EncryptionConfig` with `extractable: false`.
            3.  Saves the new `CryptoKey` handle back to IndexedDB for future use.
        *   Implement the interface methods (`encryptData`, `unwrapDEK`, etc.) by calling the `SubtleCrypto` functions defined in the interop file. Remember to use `.await()` on the returned Promises.

*   **Action 3c: Integrate via Koin Dependency Injection**
    *   **File to Modify:** `app/src/wasmJsMain/kotlin/eu/torvian/chatbot/app/koin/wasmJsModule.kt`.
    *   **Action:** Change the `CryptoProvider` binding to provide an instance of the new `WasmJsWebCryptoProvider`. Remove any old or placeholder providers.

**Example Change:**
```kotlin
single<CryptoProvider> {
    // Use the secure Web Crypto API provider for WASM/JS.
    WasmJsWebCryptoProvider(encryptionConfig)
}
```

##### **Task 4: Verification & Testing**

After implementation, it's crucial to verify that all platforms continue to function correctly.

1.  **JVM/Desktop:** Run the desktop application. Verify that login, logout, and subsequent app launches (which involve reading stored tokens) work as expected.
2.  **Android:** Run the Android application. Perform the same login/logout/relaunch tests.
3.  **WASM/JS (Browser):**
    *   Open the application in a browser.
    *   Using the browser's developer tools, go to the "Application" tab.
    *   Verify that an `IndexedDB` database named "ChatbotCrypto" is created upon first login. Inside, you should see a `KeyStore` object store containing an entry for your KEK (`kek_v1`).
    *   In `LocalStorage` (or wherever `FileSystemTokenStorage` is pointing), verify that the stored token data is encrypted (appears as a long, random-looking Base64 string).
    *   Verify that login, logout, and page reloads (which require decrypting the stored tokens) all function correctly.

#### **4. Acceptance Criteria**

The project is considered "done" when:
*   The `CryptoProvider` interface is fully asynchronous.
*   The JVM and Android applications compile and run correctly with the updated interface.
*   The WASM/JS application uses the `WasmJsWebCryptoProvider` for all cryptographic operations.
*   Encrypted tokens and a non-extractable key handle are successfully stored and retrieved in the browser.
*   Authentication functionality (login, logout, session persistence) is verified to be working on all target platforms.

---


---

### **Reference Implementation: Code Changes for Secure `CryptoProvider`**

This document contains the new and modified code required to implement the secure, asynchronous `CryptoProvider`.

#### **1. `commonMain` Source Set**

##### **`CryptoProvider.kt` (Modified)**
The core interface is updated to use `suspend` functions to support asynchronous browser APIs.

```kotlin
// file: chatbot/common/src/commonMain/kotlin/eu/torvian/chatbot/common/security/CryptoProvider.kt
package eu.torvian.chatbot.common.security

import arrow.core.Either

/**
 * Interface for cryptographic operations.
 * NOTE: The methods are suspend functions to accommodate asynchronous cryptographic
 * APIs like the Web Crypto API in browsers.
 */
interface CryptoProvider {
    suspend fun generateDEK(): Either<CryptoError, String>
    suspend fun encryptData(plainText: String, dek: String): Either<CryptoError, String>
    suspend fun decryptData(cipherText: String, dek: String): Either<CryptoError, String>
    suspend fun wrapDEK(dek: String): Either<CryptoError, String>
    suspend fun unwrapDEK(wrappedDek: String, kekVersion: Int): Either<CryptoError, String>
    fun getKeyVersion(): Int
}
```

##### **`FileSystemTokenStorage.kt` (No Changes Required)**
No changes are needed in this file. The `either` block from Arrow's Raise DSL correctly handles the new `suspend` nature of the `CryptoProvider` methods when `.bind()` is called.

---

#### **2. `jvmMain` / `androidMain` Source Set**

##### **`AESCryptoProvider.kt` (Modified)**
The existing synchronous implementation is adapted to the `suspend` interface by wrapping blocking calls in an appropriate coroutine context.

```kotlin
// file: chatbot/common/src/jvmMain/kotlin/eu/torvian/chatbot/common/security/AESCryptoProvider.kt
package eu.torvian.chatbot.common.security

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
// ... other java imports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AESCryptoProvider(private val config: EncryptionConfig) : CryptoProvider {
    // ... (properties and companion object are unchanged) ...

    override suspend fun generateDEK(): Either<CryptoError, String> = withContext(Dispatchers.IO) {
        generateRandomKey(algorithm, keySizeBits)
    }

    // APPLY THE `withContext(Dispatchers.IO) { either { ... } }` PATTERN
    // TO ALL OTHER OVERRIDDEN SUSPEND METHODS:
    // - encryptData
    // - decryptData
    // - wrapDEK
    // - unwrapDEK

    // Example for wrapDEK:
    override suspend fun wrapDEK(dek: String): Either<CryptoError, String> = withContext(Dispatchers.IO) {
        either {
            catch({
                val dekKey = secretKeyFromBase64(dek).bind()
                val kek = currentKek.bind()
                // ... rest of original method implementation
            }) { e: Exception ->
                raise(CryptoError.EncryptionError("Failed to wrap DEK: ${e.message}"))
            }
        }
    }
    
    // ... (getKeyVersion and private helper methods are unchanged) ...
}
```

---

#### **3. `wasmJsMain` Source Set**

This section contains all the new code for the secure browser implementation.

##### **`WebCrypto.kt` (New File)**
JS interop definitions for the Web Crypto API.

```kotlin
// file: chatbot/app/src/wasmJsMain/kotlin/eu/torvian/chatbot/app/external/WebCrypto.kt
package eu.torvian.chatbot.app.external

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

typealias JsAny = Any

@JsName("window.crypto.subtle")
external object SubtleCrypto {
    fun generateKey(algorithm: JsAny, extractable: Boolean, keyUsages: Array<String>): Promise<CryptoKey>
    fun importKey(format: String, keyData: ArrayBuffer, algorithm: JsAny, extractable: Boolean, keyUsages: Array<String>): Promise<CryptoKey>
    fun exportKey(format: String, key: CryptoKey): Promise<ArrayBuffer>
    fun wrapKey(format: String, key: CryptoKey, wrappingKey: CryptoKey, wrapAlgorithm: JsAny): Promise<ArrayBuffer>
    fun unwrapKey(format: String, wrappedKey: ArrayBuffer, unwrappingKey: CryptoKey, unwrapAlgorithm: JsAny, unwrappedKeyAlgorithm: JsAny, extractable: Boolean, keyUsages: Array<String>): Promise<CryptoKey>
    fun encrypt(algorithm: JsAny, key: CryptoKey, data: ArrayBuffer): Promise<ArrayBuffer>
    fun decrypt(algorithm: JsAny, key: CryptoKey, data: ArrayBuffer): Promise<ArrayBuffer>
}

external interface CryptoKey

fun ByteArray.toJsArrayBuffer(): ArrayBuffer = Uint8Array(this.toTypedArray()).buffer
fun ArrayBuffer.toByteArray(): ByteArray = Uint8Array(this).asDynamic().unsafeCast<ByteArray>()
```

##### **`IndexedDB.kt` (New File)**
A simple, promise-based wrapper for IndexedDB `get` and `put` operations.

```kotlin
// file: chatbot/app/src/wasmJsMain/kotlin/eu/torvian/chatbot/app/external/IndexedDB.kt
package eu.torvian.chatbot.app.external

import kotlin.js.Promise

object IndexedDB {
    fun get(dbName: String, storeName: String, key: String): Promise<JsAny?> {
        return Promise { resolve, reject ->
            val request = js("window.indexedDB.open(dbName, 1)")
            request.onerror = { event -> reject(Error("IndexedDB error: ${event.type}")) }
            request.onupgradeneeded = { event -> event.target.result.createObjectStore(storeName) }
            request.onsuccess = { event ->
                val getRequest = event.target.result.transaction(storeName, "readonly").objectStore(storeName).get(key)
                getRequest.onsuccess = { resolve(getRequest.result) }
                getRequest.onerror = { reject(Error("Failed to get from IndexedDB")) }
            }
        }
    }

    fun put(dbName: String, storeName: String, key: String, value: JsAny): Promise<Unit> {
        return Promise { resolve, reject ->
            val request = js("window.indexedDB.open(dbName, 1)")
            request.onerror = { event -> reject(Error("IndexedDB error: ${event.type}")) }
            request.onupgradeneeded = { event -> event.target.result.createObjectStore(storeName) }
            request.onsuccess = { event ->
                val putRequest = event.target.result.transaction(storeName, "readwrite").objectStore(storeName).put(value, key)
                putRequest.onsuccess = { resolve(Unit) }
                putRequest.onerror = { reject(Error("Failed to put to IndexedDB")) }
            }
        }
    }
}
```

##### **`WasmJsWebCryptoProvider.kt` (New File)**
The secure `CryptoProvider` implementation using Web Crypto and IndexedDB.

```kotlin
// file: chatbot/app/src/wasmJsMain/kotlin/eu/torvian/chatbot/app/security/WasmJsWebCryptoProvider.kt
package eu.torvian.chatbot.app.security

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import eu.torvian.chatbot.app.external.*
import eu.torvian.chatbot.common.security.CryptoError
import eu.torvian.chatbot.common.security.CryptoProvider
import eu.torvian.chatbot.common.security.EncryptionConfig
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class WasmJsWebCryptoProvider(private val config: EncryptionConfig) : CryptoProvider {
    private val kekAlgorithm = js("{ name: 'AES-KW' }")
    private val dekAlgorithm = js("{ name: 'AES-GCM', length: 256 }")
    private fun dataCipherAlgorithm(iv: ArrayBuffer) = js("{ name: 'AES-GCM', iv: iv }")

    private val dbName = "ChatbotCrypto"
    private val keyStoreName = "KeyStore"
    private val kekKeyName = "kek_v"
    private var cachedKek: CryptoKey? = null

    private suspend fun getKek(version: Int): Either<CryptoError, CryptoKey> = either {
        if (cachedKek != null) return@either cachedKek!!
        catch({
            val storedKey = IndexedDB.get(dbName, keyStoreName, "$kekKeyName$version").await() as? CryptoKey
            if (storedKey != null) {
                cachedKek = storedKey
                return@catch storedKey
            }
            
            val rawKeyString = config.masterKeys[version] ?: raise(CryptoError.KeyVersionNotFound(version))
            val rawKeyBytes = Base64.decode(rawKeyString)
            val importedKey = SubtleCrypto.importKey(
                "raw", rawKeyBytes.toJsArrayBuffer(), kekAlgorithm,
                false, // NOT EXTRACTABLE
                arrayOf("wrapKey", "unwrapKey")
            ).await()

            IndexedDB.put(dbName, keyStoreName, "$kekKeyName$version", importedKey).await()
            cachedKek = importedKey
            importedKey
        }) { e -> raise(CryptoError.ConfigurationError("Failed to load or import KEK: ${e.message}", e)) }
    }

    override suspend fun generateDEK(): Either<CryptoError, String> = either {
        catch({
            val dek = SubtleCrypto.generateKey(dekAlgorithm, true, arrayOf("encrypt", "decrypt")).await()
            val rawDek = SubtleCrypto.exportKey("raw", dek).await()
            Base64.encode(rawDek.toByteArray())
        }) { e -> raise(CryptoError.KeyGenerationError("Failed to generate DEK: ${e.message}", e)) }
    }

    override suspend fun encryptData(plainText: String, dek: String): Either<CryptoError, String> = either {
        catch({
            val dekKey = SubtleCrypto.importKey("raw", Base64.decode(dek).toJsArrayBuffer(), "AES-GCM", true, arrayOf("encrypt")).await()
            val iv = js("window.crypto.getRandomValues(new Uint8Array(12))").unsafeCast<ByteArray>().toJsArrayBuffer()
            val encryptedData = SubtleCrypto.encrypt(dataCipherAlgorithm(iv), dekKey, plainText.encodeToByteArray().toJsArrayBuffer()).await()
            Base64.encode(iv.toByteArray() + encryptedData.toByteArray())
        }) { e -> raise(CryptoError.EncryptionError("Failed to encrypt data: ${e.message}", e)) }
    }

    override suspend fun decryptData(cipherText: String, dek: String): Either<CryptoError, String> = either {
        catch({
            val dekKey = SubtleCrypto.importKey("raw", Base64.decode(dek).toJsArrayBuffer(), "AES-GCM", true, arrayOf("decrypt")).await()
            val combined = Base64.decode(cipherText)
            val iv = combined.copyOfRange(0, 12).toJsArrayBuffer()
            val encryptedData = combined.copyOfRange(12, combined.size).toJsArrayBuffer()
            val decryptedBytes = SubtleCrypto.decrypt(dataCipherAlgorithm(iv), dekKey, encryptedData).await()
            decryptedBytes.toByteArray().decodeToString()
        }) { e -> raise(CryptoError.DecryptionError("Failed to decrypt data: ${e.message}", e)) }
    }

    override suspend fun wrapDEK(dek: String): Either<CryptoError, String> = either {
        catch({
            val kek = getKek(config.keyVersion).bind()
            val dekToWrap = SubtleCrypto.importKey("raw", Base64.decode(dek).toJsArrayBuffer(), "AES-GCM", true, arrayOf()).await()
            val wrappedDek = SubtleCrypto.wrapKey("raw", dekToWrap, kek, kekAlgorithm).await()
            Base64.encode(wrappedDek.toByteArray())
        }) { e -> raise(CryptoError.EncryptionError("Failed to wrap DEK: ${e.message}", e)) }
    }

    override suspend fun unwrapDEK(wrappedDek: String, kekVersion: Int): Either<CryptoError, String> = either {
        catch({
            val kek = getKek(kekVersion).bind()
            val unwrappedDekKey = SubtleCrypto.unwrapKey(
                "raw", Base64.decode(wrappedDek).toJsArrayBuffer(), kek, kekAlgorithm, dekAlgorithm,
                true, // The unwrapped DEK must be extractable
                arrayOf("encrypt", "decrypt")
            ).await()
            val rawDekBytes = SubtleCrypto.exportKey("raw", unwrappedDekKey).await()
            Base64.encode(rawDekBytes.toByteArray())
        }) { e -> raise(CryptoError.DecryptionError("Failed to unwrap DEK: ${e.message}", e)) }
    }

    override fun getKeyVersion(): Int = config.keyVersion
}
```

##### **`wasmJsModule.kt` (Modified)**
The Koin module is updated to provide the new secure `CryptoProvider`.

```kotlin
// file: chatbot/app/src/wasmJsMain/kotlin/eu/torvian/chatbot/app/koin/wasmJsModule.kt
package eu.torvian.chatbot.app.koin

import eu.torvian.chatbot.app.main.AppConfig
import eu.torvian.chatbot.app.security.WasmJsWebCryptoProvider
import eu.torvian.chatbot.app.service.auth.FileSystemTokenStorage
// ... other imports

fun wasmJsModule(appConfig: AppConfig, encryptionConfig: EncryptionConfig) = module {
    single<CryptoProvider> {
        // Use the secure Web Crypto API provider for WASM/JS.
        WasmJsWebCryptoProvider(encryptionConfig)
    }
    // ... other bindings are unchanged
}
```