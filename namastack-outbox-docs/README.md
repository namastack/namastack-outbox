# Website

This website is built using [Docusaurus](https://docusaurus.io/), a modern static website generator.

## Installation

```bash
yarn
```

## Local Development

```bash
yarn start
```

This command starts a local development server and opens up a browser window. Most changes are reflected live without having to restart the server.

## Build

```bash
yarn build
```

This command generates static content into the `build` directory and can be served using any static contents hosting service.

## Deployment

Using SSH:

```bash
USE_SSH=true yarn deploy
```

Not using SSH:

```bash
GIT_USER=<Your GitHub username> yarn deploy
```

If you are using GitHub pages for hosting, this command is a convenient way to build the website and push to the `gh-pages` branch.

## Outbox overview video

The homepage and `/outbox/` embed the version 10 showreel through `OutboxShowreel` in `src/components/MarketingPage/index.tsx`. Assets live in `static/videos/`: the H.264/AAC MP4, poster, and English WebVTT captions. Playback is user-initiated with native controls and inline mobile playback; `preload="none"` avoids downloading the video on page load.

The MP4 comes from `namastack-showreel/out/namastack-showreel-v10.mp4`, remuxed with `-c copy -movflags +faststart` for web playback without re-encoding. Captions come from the matching `out/namastack-story.srt`. When replacing the film, update all three assets together and change the versioned URLs in the component to avoid stale browser caches. These assets ship with the normal static site build and require no external video service.
