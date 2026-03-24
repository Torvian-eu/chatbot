# MCP Server Configuration Guide

## Table of Contents
1. [Introduction](#introduction)
2. [MCP server configuration](#mcp-server-configuration)
3. [Using MCP servers](#using-mcp-servers)

## Introduction
This guide provides information on how to configure and use MCP servers with the chatbot.

## MCP server configuration
A new MCP server can be added by clicking on the `+` button in the MCP servers section of the settings page.
The following information is required to add a new MCP server:
* Name: A name for the MCP server
* Description: A description for the MCP server (optional)
* Tool name prefix: A prefix for the tool names (optional)
* Command: The command to start the MCP server
* Arguments: The arguments to start the MCP server
* Environment variables: The environment variables to start the MCP server (optional)
* Working directory: The working directory to start the MCP server (optional)
* Auto-start options: The auto-start options for the MCP server (optional)
  * On enable: Start the MCP server when a tool is enabled
  * On launch: Start the MCP server when the application launches
* Auto-stop timeout: The auto-stop timeout for the MCP server in seconds (optional)

Notes:
- The Command, Arguments, and Environment variables are supplied by the MCP server author. For example, the [Serena MCP server](https://github.com/oraios/serena) uses the command `uvx` with arguments `--from git+https://github.com/oraios/serena serena start-mcp-server`.
- Using commands like `uvx` or `npx` can be dangerous due to supply chain attacks, as they pull the latest version from the repository without version control. A compromised repository could deliver malicious code. To mitigate this risk, clone the repository locally and use the `--from` argument to point to your local copy. Note that pinning a specific version with `@<version>` is insufficient, since version tags can be modified by attackers.
- Setting a tool name prefix helps the LLM distinguish between tools from different MCP servers. For example, use `github_` and `gitlab_` prefixes for GitHub and GitLab servers respectively.

## Using MCP servers
After adding an MCP server, the tools from the server will be available in the tool configuration dialog. The MCP server can be enabled or disabled for a chat session by toggling the switch in the tool configuration dialog. Enabling the MCP server will make its tools available for use in the chat session.

