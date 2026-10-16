# Governance

How decisions about Latch are made, who makes them, and how that changes as the project grows.

## Roles

| Role | Who | What they do |
|---|---|---|
| **Maintainer** | [@aspershupadhyay](https://github.com/aspershupadhyay) | Owns the roadmap, reviews and merges pull requests, cuts releases, and handles security reports |
| **Contributor** | Anyone with a merged pull request | Proposes changes through issues and pull requests |

[CODEOWNERS](.github/CODEOWNERS) decides who must review each path. As maintainers join, each area (Android app, gateways, protocol, website) gets its own owners there.

## How decisions are made

- **The plan lives in the repository.** The [handbook](handbook/README.md) is the product and engineering plan, and its [roadmap table](handbook/17-plan-review-and-revised-delivery.md) is the work queue. Changing either is a pull request like any other.
- **Lasting technical choices get a decision record.** Anything that changes the device protocol, a capability, or a safety rule needs an entry in [docs/adr](docs/adr/README.md): the problem, the options considered, the decision, and how to undo it.
- **Safety rules win ties.** When speed or convenience conflicts with the [safety model](handbook/08-safety-privacy-security.md), the safety model wins unless the owner explicitly and visibly waives a rule for their own apps.
- **Disagreements** are settled in the pull request or issue. If they can't be settled there, the maintainer decides and records why.

## Merging

A pull request merges when every CI check is green on its latest commit and no review thread is open. CI runs the Rust and TypeScript tests, both gateways end to end, the official MCP SDK client, the Android build, the real app on an emulator, the container image, and a dependency audit.

## Releases

Every merge to `main` publishes a signed beta build. Tagged releases follow [docs/operations/release.md](docs/operations/release.md): a real-device check on at least two phones, a dry run, then a signed tag with checksums, a software bill of materials, and build provenance. Changes are listed in [CHANGELOG.md](CHANGELOG.md).

## Security

Report vulnerabilities privately, as described in [SECURITY.md](SECURITY.md). Never in a public issue.

## Conduct

Everyone taking part follows the [Code of Conduct](CODE_OF_CONDUCT.md).
