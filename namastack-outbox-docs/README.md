# Namastack Outbox Documentation Workflow

This document explains how to manage versioned documentation using [Mike](https://github.com/jimporter/mike).

## Prerequisites

Install the required dependencies:

```bash
cd namastack-outbox-docs
pip install -r requirements.txt
```

## Commands

### Preview Documentation Locally

To preview the versioned documentation locally:

```bash
mike serve
```

This starts a local server (default: `http://localhost:8000`) showing all deployed versions.

### Deploy a New Version

To deploy documentation for a specific version:

```bash
mike deploy <version> [alias]
```

Examples:

```bash
# Deploy version 1.0.0
mike deploy 1.0.0

# Deploy version 2.0.0 and set it as "latest"
mike deploy 2.0.0 latest

# Deploy and push to gh-pages immediately
mike deploy 1.0.0 --push
```

### Update an Existing Version

To update an already deployed version:

```bash
mike deploy <version> --update-aliases
```

### Set the Default Version

Set which version visitors see when accessing the root URL:

```bash
mike set-default <version>
```

Example:

```bash
mike set-default latest
```

### List All Versions

View all deployed documentation versions:

```bash
mike list
```

### Delete a Version

Remove a specific version:

```bash
mike delete <version>
```

Example:

```bash
mike delete 0.9.0
```

### Retitle a Version

Change the display title of a version:

```bash
mike retitle <version> <title>
```

Example:

```bash
mike retitle 1.0.0 "1.0.0 (LTS)"
```

## Editing an Older Version

Mike stores **rendered HTML** in the `gh-pages` branch, not the source Markdown files. To edit documentation for an older version:

1. **Checkout the source at that version's tag:**
   ```bash
   git checkout -b fix-docs-v1.0 v1.0.0
   ```

2. **Edit the Markdown files** in `namastack-outbox-docs/docs/`

3. **Re-deploy that version:**
   ```bash
   cd namastack-outbox-docs
   mike deploy 1.0.0 --push
   ```

## Deployment to Production

When you run `mike deploy --push`, it pushes the built documentation to the `gh-pages` branch. This triggers the GitHub Actions workflow (`.github/workflows/deploy-docs.yml`) which deploys the documentation to production.

## Common Workflow

```bash
# 1. Make changes to your docs in docs/

# 2. Preview locally (optional)
mike serve

# 3. Deploy the new version
mike deploy 2.0.0 latest --update-aliases --push

# 4. Set as default (if needed)
mike set-default latest --push
```

## Useful Flags

| Flag | Description |
|------|-------------|
| `--push` | Push changes to remote immediately |
| `--update-aliases` | Update existing aliases |
| `--branch <branch>` | Specify target branch (default: `gh-pages`) |
| `--remote <remote>` | Specify remote (default: `origin`) |
| `--force` | Force push |


