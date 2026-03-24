# LLM Configuration Guide

## Table of Contents
1. [Introduction](#introduction)
2. [LLM Provider Configuration](#llm-provider-configuration)
3. [LLM Model Configuration](#llm-model-configuration)
4. [LLM Model Settings Configuration](#llm-model-settings-configuration)
5. [LLM Configuration in the Chatbot](#llm-configuration-in-the-chatbot)

## Introduction
This guide provides information on how to configure LLM providers, models and model settings. And how to use them in the chatbot. The chatbot currently supports the following LLM providers: OpenAI and Ollama.

## LLM Provider Configuration
A new provider can be added in the settings of the chatbot. Go to the settings screen and select the providers tab. Click on the add button and fill in the form. The form asks for the following information:
* Name: A name for the provider
* Description: A description for the provider (optional)
* Base URL: The base URL for the provider
* Type: The type of the provider (OpenAI, Ollama)
* Credential: The API key for the provider (optional for local providers like Ollama)
 
List of base URLs for different providers:
* OpenAI: https://api.openai.com/v1
* Gemini: https://generativelanguage.googleapis.com/v1beta/openai
* OpenRouter: https://openrouter.ai/api/v1
* Ollama: http://localhost:11434

Websites for acquiring API keys:
* OpenAI: https://platform.openai.com/api-keys
* Gemini: https://aistudio.google.com/api-keys
* OpenRouter: https://openrouter.ai/workspaces/default/keys

Things to consider:
- Cost: Cloud providers charge for API calls. Local providers like Ollama are free, but require expensive hardware to run the larger models. Smaller models like Qwen3-8B can run on regular desktop computers with a decent GPU (for example, a GTX 1070 with 8GB VRAM).
- Privacy: Cloud providers may store and use your data for model training, especially with free models. Paid models typically have stricter data protection policies. Using a local provider like Ollama is a good option if privacy is of the utmost importance.
- Rate limits: Cloud providers have rate limits. This means that you can only make a certain number of API calls per minute (and/or hour, day, etc., depending on the provider, and your current tier). When you reach the rate limit, you will have to wait until the limit resets before you can make more API calls. Free models often have much stricter rate limits than paid models, which makes them unsuitable for agentic LLM responses.
- Model restrictions: Some providers may restrict which models you can use with their service. The very best models are usually only available on the higher paid tiers.

Tips:
- OpenRouter and Gemini offer many free models with minimal sign-up requirements (OpenRouter requires a one-time $10 payment, while Gemini has no fees). OpenAI also offers daily free tokens with some of their top models. Keep in mind that free models typically allow providers to collect and use your data for training, so be cautious about what you process with them.

## LLM Model Configuration
A new model can be added in the settings of the chatbot. Go to the settings screen and select the models tab. Click on the add button and fill in the form. The form asks for the following information:
* Name: The name of the model as it is called by the provider (e.g. gpt-3.5-turbo, llama3-30b, etc.)
* Display Name: A display name for the model (optional)
* Provider: The provider for the model
* Type: The type of the model (chat, embedding, etc.)
* Active: Whether the model is active or not
* Capabilities: The capabilities of the model (tool calling, image input, streaming output, etc.)


Tips:
- Use the chat model type for the chatbot. Embedding models are used for generating embeddings for text, which enable semantic search capabilities (used by Retrieval-Augmented Generation (RAG) for processing large amounts of text data).
- Most modern models support tool calling and streaming output, so you should enable those options. Tool calling allows you to use tools with the model, while streaming output enables the chatbot to display responses as they're generated, providing faster and more responsive user interactions.

## LLM Model Settings Configuration
A new settings profile can be added in the settings of the chatbot. Go to the settings screen and select the settings tab. Select the model for which you want to add a settings profile. Click on the add button and fill in the form. The form asks for the following information:
* Name: A name for the settings profile (default, creative, etc.)
* System Message: The system message for the model (optional)
* Temperature: The temperature for the model (optional)
* Custom Parameters: Custom parameters for the model (optional)

Tips:
- All fields except the name are optional. If you don't specify a value, the default value from the provider will be used.
- The system message should be a general instruction for the model on how to behave. For example, you can tell the model to act as a helpful assistant or as a specific character or role. The system message is given more importance than user messages and is included in every request.
- The temperature controls the randomness of the model's output. A higher temperature means the model will be more random, while a lower temperature means the model will be more deterministic. The value should be between 0.0 and 1.0. A good starting point is 0.7.

## LLM Configuration in the Chatbot
To use a model in the chatbot, you need to select a model and a settings profile. Go to the chat screen, create a new chat session, and select the model and settings profile you want to use. The model and settings profile are stored in the session, so they will be used for all future requests in that session.
