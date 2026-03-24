# Contributing to Torvian Chatbot

We appreciate your interest in the Torvian Chatbot! While this project is primarily developed for internal use, we welcome contributions from the community to help improve it. Your feedback, bug reports, feature suggestions, and code contributions are valuable.

Please use the following guidelines for contributing:

*   **Reporting Bugs:** Found a bug? Please open an [issue](https://github.com/rwachters/chatbot/issues/new/choose) using the bug report template. Provide as much detail as possible, including steps to reproduce, expected behavior, and actual behavior.
*   **Suggesting Features & Ideas:** Have an idea for a new feature or an improvement? Feel free to open an [issue](https://github.com/rwachters/chatbot/issues/new/choose) to track it, or share your thoughts in the [GitHub discussion forum](https://github.com/rwachters/chatbot/discussions) for broader discussion.
*   **Asking Questions & Getting Support:** For general questions, usage help, or support, please use the [GitHub discussion forum](https://github.com/rwachters/chatbot/discussions). This helps keep the Issues tracker focused on actionable bugs and features.
*   **Code Contributions (Pull Requests):** We welcome code contributions! If you're planning a significant change or a new feature, please consider opening an issue or discussing it in the forums first. This helps ensure alignment with the project's direction and avoids duplicated effort.

We strive to review all contributions promptly and appreciate your patience.

---

## Code of Conduct

To ensure a welcoming and inclusive environment for everyone, we encourage all contributors to abide by our [Code of Conduct](CODE_OF_CONDUCT.md) (if you create one).

---

## Development Setup

If you plan to contribute code, here are some brief steps to get started with local development:

1.  **Fork the repository on GitHub:**
    *   Navigate to the `rwachters/chatbot` repository on GitHub.
    *   Click the **"Fork" button** (usually located in the top-right corner of the page) to create your own copy of the repository under your GitHub account.
2.  **Clone your fork:**
    ```bash
    git clone https://github.com/YOUR_USERNAME/chatbot.git
    cd chatbot
    ```
3.  **Install dependencies:** Follow the "Quick Start" prerequisites in the `README.md` to ensure you have JDK 21 and Git.
4.  **Build and Run:** Use the Gradle commands provided in `README.md` to build and run the server and desktop application locally.
5.  **Create a new branch:** Before making changes, create a new branch for your feature or bug fix:
    ```bash
    git checkout -b feature/your-awesome-feature
    ```
6.  **Implement and Test:** Make your changes, ensuring existing tests pass and adding new tests for new functionality if applicable.
7.  **Commit your changes:**
    ```bash
    git commit -am "feat: Add your awesome feature"
    ```
8.  **Push to your fork:**
    ```bash
    git push origin feature/your-awesome-feature
    ```
9.  **Open a Pull Request:** Navigate to your fork on GitHub and open a pull request to the `main` branch of the upstream `rwachters/chatbot` repository.

---

## Issue and Pull Request Guidelines

*   **Be Specific:** Provide clear, concise descriptions for issues and pull requests.
*   **One Thing at a Time:** Focus each issue or pull request on a single problem or feature.
*   **Refer to Issues:** Link your pull request to the relevant issue(s) it addresses (e.g., "Closes #123" or "Fixes #123").
*   **Respectful Communication:** Please maintain a positive and constructive tone in all interactions.