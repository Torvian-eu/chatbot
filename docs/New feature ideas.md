# New feature ideas

## Basic features

### Allow message streaming process to stop gracefully
  - Add Boolean field to AssistantMessage to indicate if the message is complete.
  - Add error message String field to AssistantMessage (which can be shown in the UI, together with the partial content).
  - Store partial message content in AssistantMessage table, in case the stream was interrupted. (either due to an error, or because the user stopped the streaming)
  - In the UI, the "send" button should transform into a "stop" button while the message is streaming. 

### Allow recursive deletion of messages. (in addition to single message deletion)
  - The server module is already capable of this, but the ktor route needs to be examined. In `configureMessageRoutes.kt` we have a route `delete<MessageResource.ById>`, where we use a query parameter "mode=single" to perform a non-recursive delete and "mode=recursive" to perform a recursive delete. However, we use the Ktor Resources plugin, and we should examine if a query parameter can be configured for the route declaritively instead, so that it can be validated at compile time.
  - Add a "Delete Thread" button to the message actions menu. (see MessageActionRow.kt)
    (or hide it under a "More" actions button, to show a popup menu with extra actions, less commonly used)

### Insert message above/below
Add an "Insert Above" and "Insert Below" button to the more actions menu. (See MessageActionRow.kt)

### Make message content selectable

### Make messages collapsible (useful for long messages)

### Make message content copyable to clipboard
A "Copy" button is already available in the message actions menu, but it needs to be wired to an action function. (see MessageActionRow.kt)

### Make currently displayed message thread copyable to clipboard

### Make chat session copyable to file/clipboard as JSON (for importing into other apps)

### Make chat session importable from file/clipboard (for ChatGPT export format)

### Make messages searchable

### Add Send button to message edit area (in addition to Save/Cancel)
This action triggers a reply to the edited message from the assistant/LLM. It also creates a new branch in the conversation. (See composable MessageContent.kt)

### Add option to regenerate assistant messages
A "Regenerate" button is already available in the message actions menu, but it needs to be wired to an action in the view model. (see MessageActionRow.kt)
This action should trigger a new response from the assistant/LLM, based on the current message context. It must also create a new branch in the conversation. (See MessageActionRow.kt)

### Allow certain features to be disabled on a per-user basis
The user should be able to configure this in their profile. (Requires: user accounts feature)

---

## Intermediate features

### User accounts
Each user has their own set of credentials, models, settings, and chat history. Some things could be shared between users, such as public providers, models and settings. Certain users could be assigned special privileges, such as the ability to manage other users, or add public providers, models and settings. There must be at least one admin user, who has all privileges (or has at least the capability to assign privileges to theirself and other users)

---

## Advanced features

### Allow basic tool calling for LLM models that support it

### Add option to add tools to MCP server
Tools are functions that the LLM can execute. They are defined by the user and can be used to interact with the outside world. For instance a tool could be defined to search the web for information. The LLM could then use this tool to search the web for information to answer a question.

### Add option to load knowledge base into MCP server
This knowledge base can be queried by the LLM to answer questions. It can also add items to the knowledge base itself. The knowledge base is a combination of entities and relationships between them. A query could consist of an entity and an integer to indicate the level of relationships to traverse. The result of the query is a list of entities and relationships that can be used to answer the question. For instance a level 1 query for "John" could return:
  - John is a person
  - John is 30 years old
  - John lives in New York
A level 2 query for "John" could return:
  - John is a person
  - John is 30 years old
  - John lives in New York
  - New York is a city
  - New York is in the USA
  - New York has a population of 8.4 million
  - etc.

### AI Everywhere
Alow LLM agent to control the app itself. For instance: modifying a chat session, Summarizing it, or creating a new session.

---

## Performance improvements

### Override equals & hashCode for ChatMessage class
Make equals and hashCode methods based on the `id` and `updatedAt` fields only. This should improve the performance of the UI when rendering messages. (See MessageList.kt)

### Avoid loading the extended Icons library for Compose, to reduce app size
See Gradle dependencies: `libs.version.toml` and `app/build.gradle.kts`. We now load the extended Icons library: `implementation(compose.materialIconsExtended)`