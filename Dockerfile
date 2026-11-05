# syntax=docker/dockerfile:1
#
# Home Assistant add-on image for PiKaraoke.
#
# This repository holds no PiKaraoke source of its own. The upstream project is
# fetched at build time from the branch named in UPSTREAM_REF, and the add-on's
# own changes are applied on top as a patch (see extension/). That way an
# upstream release is picked up by changing one argument, and a conflict shows
# up at build time rather than at runtime.
#
# The Supervisor builds this itself when the add-on has no prebuilt image, so
# the Dockerfile has to work for every architecture the add-on claims. Alpine
# keeps the base small, and the static ffmpeg build avoids depending on the
# distro's ffmpeg package (which does not carry the rubberband filter on all
# architectures, and is what PiKaraoke needs for pitch shifting).

FROM alpine:3.24 AS source

# Which upstream source to build. A branch tracks upstream's newest commits; a
# tag pins a release. Either way, this is the single place to change to update.
ARG UPSTREAM_REPO=https://github.com/vicwomg/pikaraoke.git
ARG UPSTREAM_REF=master

# The extension patch is copied in before the clone. The build caches each
# step, so copying the patch first means a change to the patch invalidates the
# clone as well. Otherwise the clone is reused from an earlier build and the
# new patch is applied to an upstream tree that is no longer the one it was
# written against, which fails in a way that looks like a broken patch.
COPY extension/0001-ha-addon.patch /tmp/extension.patch

# git: the upstream source is fetched from git during the build.
RUN apk add --no-cache git

# The clone keeps its history, and it is not removed yet: the patch is applied
# with a three-way merge below, and that merge needs the git history to find
# the upstream file the patch was written against. When upstream changes the
# lines the patch touches, the merge folds the change into the patched file
# instead of refusing to apply it.
RUN git clone --branch "${UPSTREAM_REF}" "${UPSTREAM_REPO}" /src

# The patch is the extension: Ingress path handling, the lyrics sidecar and
# the Home Assistant option plumbing. It is kept out of the upstream tree so
# that updating upstream is a matter of re-applying one patch.
#
# If the merge still conflicts, the build fails here, where the message says
# which file disagrees, rather than later at runtime.
RUN cd /src \
    && git apply --3way --verbose /tmp/extension.patch \
    && rm -rf /src/.git /tmp/extension.patch


FROM alpine:3.24

ARG BUILD_FROM
ARG BUILD_ARCH

WORKDIR /app

# Install uv
COPY --from=ghcr.io/astral-sh/uv:0.5.26 /uv /uv/bin/
ENV PATH="/uv/bin:$PATH"
ENV UV_BREAK_SYSTEM_PACKAGES=1

# Copy static ffmpeg binaries. 7.0 is a bit old, but significantly smaller while supporting librubberband
COPY --from=mwader/static-ffmpeg:7.0 /ffprobe /ffmpeg /usr/local/bin/

# node, not deno: deno's aarch64 apk build lags badly (an old arm64 Pi target),
# while nodejs ships current in main on every arch. yt-dlp uses either.
# tzdata: without the zoneinfo files, musl ignores a TZ zone name and stays on UTC
RUN apk add --no-cache python3 py3-pip nodejs curl tzdata su-exec

# Copy dependency and metadata files first for better caching
COPY --from=source /src/pyproject.toml /src/uv.lock /app/

# Install dependencies using uv (this layer is cached)
RUN uv venv /app/.venv && \
    uv pip install --no-cache-dir -r pyproject.toml

ENV VIRTUAL_ENV=/app/.venv
ENV PATH="/app/.venv/bin:$PATH"

# Install pip into the venv so that python -m pip works for yt-dlp self-upgrade
RUN uv pip install pip

COPY --from=source /src/docs /app/docs
COPY --from=source /src/pikaraoke /app/pikaraoke

# Final install of the package itself (fast)
RUN uv pip install --no-cache-dir --no-deps .

# Directories must exist prior to run, otherwise the local volume persistence won't work.
# /data is where the Supervisor puts the add-on's options.json and its private data.
RUN mkdir -p /data /config /share/pikaraoke /home/pikaraoke/.pikaraoke && \
    chown -R root:root /data /config /share

VOLUME ["/data", "/config", "/share"]
EXPOSE 5555

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:5555/ || exit 1

COPY run.sh /run.sh
RUN chmod +x /run.sh && sed -i 's/\r$//' /run.sh

CMD ["/run.sh"]
