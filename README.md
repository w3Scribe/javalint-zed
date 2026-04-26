# java-lint — Zed Extension

A Java linting extension for the [Zed](https://zed.dev) editor powered by [Checkstyle](https://checkstyle.org).

## What it does

- 🔴 Shows lint errors **inline** in the editor as red underlines
- 🟡 Shows warnings as yellow underlines  
- ⚡ Runs on every file save automatically
- 🎨 Ships with Google Java Style rules out of the box
- 🔧 Fully customizable via `checkstyle.xml` in your project root

## Install & Build

### Prerequisites
- Java 21+
- Rust + Cargo (`https://rustup.rs`)

### Build
```powershell
.\build.ps1
```

### Install in Zed
1. `Ctrl+Shift+P` → `zed: install dev extension`
2. Select this folder
3. Done ✅

## Customize Lint Rules

Drop a `checkstyle.xml` in your **project root** to override the default rules:

```
my-project/
├── pom.xml
├── checkstyle.xml   ← your custom rules
└── src/
```

## Default Rules Included

| Category | Rules |
|---|---|
| Naming | Classes, methods, variables, constants |
| Imports | No star imports, no unused imports |
| Whitespace | Spacing, braces, operators |
| Complexity | Method length, cyclomatic complexity |
| Coding | equals/hashCode pairs, no finalizers |
| Javadoc | Missing docs on public methods (warning) |

## Example Errors it Catches

```java
import java.util.*;          // ❌ star import
byte a = 128;                // ❌ value out of range (caught by javac/jdtls)
if (condition) doSomething() // ❌ missing braces
String s = new String("hi"); // ❌ unnecessary instantiation
```

## Project Structure

```
java-lint-zed/
├── Cargo.toml              # Rust extension manifest
├── extension.toml          # Zed extension manifest
├── checkstyle.xml          # Default lint rules
├── build.ps1               # Windows build script
├── src/
│   └── lib.rs              # Rust/WASM Zed extension entry point
└── shim/
    ├── CheckstyleLspShim.java   # LSP server wrapping Checkstyle
    └── MANIFEST.MF              # Jar manifest
```
