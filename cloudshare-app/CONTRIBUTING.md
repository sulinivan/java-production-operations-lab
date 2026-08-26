# Contributing to CloudShare

Thank you for your interest in contributing to CloudShare! We welcome contributions from developers of all skill levels. By contributing to this project, you help make secure file sharing safer, faster, and more robust.

Please take a moment to read this document to understand our development process and guidelines.

---

## Table of Contents

1. [Code of Conduct](#code-of-conduct)
2. [How to Contribute](#how-to-contribute)
   - [Reporting Bugs](#reporting-bugs)
   - [Suggesting Enhancements](#suggesting-enhancements)
   - [Pull Requests](#pull-requests)
3. [Development Setup](#development-setup)
   - [Prerequisites](#prerequisites)
   - [Backend Setup (Spring Boot)](#backend-setup-spring-boot)
   - [Frontend Setup](#frontend-setup)
   - [Running with Docker Compose](#running-with-docker-compose)
4. [Style Guidelines](#style-guidelines)
   - [Commit Messages](#commit-messages)
   - [Code Style](#code-style)
5. [License](#license)

---

## Code of Conduct

We expect all contributors to adhere to a professional, respectful, and inclusive standard of behavior. Please treat others with kindness, respect, and constructive cooperation.

---

## How to Contribute

### Reporting Bugs

If you find a bug, security vulnerability, or unexpected behavior:
1. First, search the existing issues to see if it has already been reported.
2. If not, open a new issue using our **Bug Report Template** (located in [.github/TEMPLATE/bug_report.md](file:///d:/github/cloudshare-app/.github/TEMPLATE/bug_report.md)).
3. Provide as much detail as possible, including system context, steps to reproduce, and screenshots if applicable.

> [!WARNING]
> If you discover a critical **security vulnerability**, please **do not** open a public issue. Instead, report it privately to the maintainers' email or follow the security contact guidelines in our documentation.

### Suggesting Enhancements

If you have an idea for a new feature or improvement:
1. Search current open/closed issues to see if the feature has been proposed before.
2. Open a new issue using our **Feature Request Template** (located in [.github/TEMPLATE/feature_request.md](file:///d:/github/cloudshare-app/.github/TEMPLATE/feature_request.md)).
3. Clearly describe the problem, the proposed solution, and alternative solutions you've considered.

### Pull Requests

We welcome pull requests (PRs) for bug fixes, performance improvements, documentation updates, and new features. To submit a PR:
1. **Fork the Repository** and create a feature branch off of `main` (e.g., `feature/secure-sharing` or `bugfix/file-upload-size`).
2. Make your changes and write automated tests where applicable.
3. Ensure the project builds successfully and all tests pass locally.
4. Keep your commits clean and follow the commit structure outlined in the [Commit Messages](#commit-messages) section.
5. Push your branch to GitHub and open a Pull Request using our **PR Template** (located in [.github/TEMPLATE/pull_request_template.md](file:///d:/github/cloudshare-app/.github/TEMPLATE/pull_request_template.md)).
6. Address any feedback from code reviews.

---

## Development Setup

### Prerequisites
- **Java 17** or higher
- **Maven 3.8+**
- **Docker & Docker Compose** (for PostgreSQL, Redis, MinIO, ClamAV)
- **Node.js & npm** (if modifying the frontend)

### Backend Setup (Spring Boot)
The backend is built with Spring Boot and uses Maven.
1. Configure your local environment by copying `.env.example` to `.env`.
2. Spin up the infrastructure dependencies:
   ```bash
   docker compose up -d db cache-aside cache-security cache-ratelimit storage clamav
   ```
3. Run the backend application:
   ```bash
   ./mvnw spring-boot:run
   ```
4. Run tests:
   ```bash
   ./mvnw clean test
   ```

### Frontend Setup
The frontend is located in the `/frontend` directory and is built with HTML5, CSS, and modern JavaScript.
1. Navigate to `/frontend`:
   ```bash
   cd frontend
   ```
2. Follow any specific package installations (if any) or run a local dev server using `npm install` and `npm run dev`.

---

## Style Guidelines

### Commit Messages
We enforce clear, atomic, and structured commit messages. 
- Prefix your commits with conventional tags (e.g., `feat:`, `fix:`, `docs:`, `test:`, `refactor:`).
- Keep the first line under 50 characters, and explain the "what" and "why" in the body.
- For reference, see our [Commit Template](file:///d:/github/cloudshare-app/.github/TEMPLATE/commit_template.md). You can configure git to use this template locally:
  ```bash
  git config commit.template .github/TEMPLATE/commit_template.md
  ```

### Code Style
- **Java**: Follow Google Java Style Guide. Keep code formatted and clean.
- **JavaScript**: Follow ES6+ conventions. Use clean and readable variable/function names.
- **CSS**: Use Vanilla CSS variables, structured classes, and responsive design guidelines.

---

## License

By contributing to CloudShare, you agree that your contributions will be licensed under the project's **MIT License** terms. See the [LICENSE](file:///d:/github/cloudshare-app/LICENSE) file for more information.
