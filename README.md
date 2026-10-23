# This repository

> Replace this file with a description of what this component is and does.

This is a **component repository** of a qits project. It was created blank and attached to its
project's wrapper repository as a submodule, at `components/<component>/<repository>` — the
directory says which component it belongs to, and its own name says the role it plays
(`-service`, `-daemon`, `-frontend`, `-app`, `-cli`, `-oci`, `-javalib`/`-jslib`). Renaming it to carry a
different role suffix is how you change what kind of component it is.

The wrapper is the project: a repository that is not one of its `.gitmodules` entries is not part of
the project, and reconciling the wrapper is what puts a stray one back.

## Files

- `.config/qits/repository.yml` — this repository's qits configuration: its actions and bootstrap
  chain. It is read in-container per workspace from your branch's checkout, so editing it is an
  ordinary commit.
- `.gitignore` — build output and local state, per language. Add yours as the component grows.
